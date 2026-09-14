package org.jellyfin.androidtv.ui.playback

import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.constant.AUTO_QUALITY
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import timber.log.Timber
import java.net.InetAddress
import java.net.URI

/*
 * Adaptive bitrate for the "Auto" quality setting.
 *
 * Jellyfin sends a single stream at the bitrate the client asks for, so there is no ladder for the
 * player to switch between on its own. Instead the controller below watches the BUFFER once a
 * second and works the link out from it: over a window of W seconds of playback the player consumed
 * W seconds of media and received W plus however much the buffer gained or lost, so what arrived is
 * stream_bitrate * (W + delta) / W. That is an observation, unlike ExoPlayer's throughput meter,
 * which reported 246.8 Mbit/s on a 3 Mbit link and is no longer consulted for any decision.
 *
 * Draining means the link is below the stream rate, so step down to a rung that fits. Filling means
 * it is above, so climb to what it supports. A full buffer measures nothing at all, because the
 * player stops fetching, so from there we climb one rung at a time to discover the headroom.
 *
 * It only runs when the server is reached over the internet; on the home network there is no cap.
 */

/** Full quality: the cap used on the home network, in bits per second. */
const val AUTO_LOCAL_BPS = 200_000_000

/** The steps used while remote, in bits per second - the same rungs as the manual quality list, up to 200. */
private val LADDER_BPS = listOf(
	420_000, 720_000, 1_000_000, 1_500_000, 2_000_000, 3_000_000, 4_000_000, 5_000_000, 6_000_000,
	8_000_000, 10_000_000, 12_000_000, 15_000_000, 20_000_000, 30_000_000,
	40_000_000, 60_000_000, 80_000_000, 100_000_000, 140_000_000, 200_000_000,
)
private const val DEFAULT_REMOTE_BPS = 5_000_000
private const val PROBE_TTL_MS = 10 * 60 * 1000L
private const val PROBE_SMALL_BYTES = 500_000
private const val PROBE_LARGE_BYTES = 2_000_000
private const val PROBE_HUGE_BYTES = 8_000_000
private const val PROBE_FAST_LINK_BPS = 4_000_000
private const val PROBE_VERY_FAST_LINK_BPS = 40_000_000
private const val PROBE_SAFETY = 0.8

private const val TICK_MS = 1_000L
private const val HISTORY = 10
private const val WARMUP_MS = 15_000L
private const val STALL_TICKS = 3
private const val STALL_AHEAD_MS = 1_500L
private const val STALL_COOLDOWN_MS = 30_000L
/** Step down once the buffer is projected to run out within this, so the swap has room. */
private const val ACT_BEFORE_EMPTY_MS = 45_000L

/** Step down when the buffer is draining and has fallen this low. */
private const val BUFFER_FLOOR_MS = 20_000L

/** Step down at this depth even if the buffer looks momentarily steady: it is nearly gone. */
private const val BUFFER_CRITICAL_MS = 8_000L

/** Treat the buffer as full, and therefore uninformative, at this depth. */
private const val BUFFER_FULL_MS = 120_000L

/** Ignore drains smaller than this fraction of real time as measurement noise. */
private const val DRAIN_NOISE = 0.05
private const val DOWN_COOLDOWN_MS = 20_000L
private const val DOWN_SAFETY = 0.7
private const val UP_AHEAD_MS = 40_000L
/** Climb only to a rung the link beats by this much, so there is spare capacity to refill the
 *  buffer the swap just emptied. 1.6 was so demanding it sat at under half the link; 1.25
 *  settles at a rung with a quarter of the link spare. */
private const val UP_HEADROOM = 1.25
private const val UP_MIN_GAIN = 1.5
private const val UP_HOLD_TICKS = 60
private const val UP_COOLDOWN_MS = 120_000L
private const val UP_AFTER_DOWN_MS = 180_000L

/** How long a rung stays off-limits after the link forced us off it. */
private const val FAILED_RETRY_MS = 300_000L
private const val NEAR_END_MS = 2_000L
private const val BUFFER_FULL_MAX_MS = 200_000L

private fun ladderFloor(bps: Long): Int = LADDER_BPS.lastOrNull { it <= bps } ?: LADDER_BPS.first()
private fun ladderIndex(bps: Int): Int = LADDER_BPS.indexOfFirst { it >= bps }.takeIf { it >= 0 } ?: LADDER_BPS.lastIndex
private fun mbit(bps: Long): String = if (bps <= 0) "unknown" else "%.1f Mbit/s".format(bps / 1_000_000.0)
private fun mbitShort(bps: Long): String = if (bps >= 10_000_000) "%.0f Mb/s".format(bps / 1_000_000.0) else "%.1f Mb/s".format(bps / 1_000_000.0)

object ServerLocality {
	/** True when the server address is on the home network (RFC 1918 or loopback) and the test override is off. */
	fun isLocal(api: ApiClient, userPreferences: UserPreferences): Boolean {
		if (userPreferences[UserPreferences.adaptiveForceRemote]) return false
		val host = runCatching { URI(api.baseUrl).host }.getOrNull() ?: return false
		if (host.equals("localhost", ignoreCase = true)) return true
		// Only IPv4 literals reach InetAddress, so this never performs a DNS lookup.
		if (!host.all { it.isDigit() || it == '.' }) return false
		val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
		return address.isSiteLocalAddress || address.isLoopbackAddress
	}
}

/** Shared between the device profile (which asks for the cap) and the controller (which moves it). */
class AdaptiveBitrateState {
	/** Cap while remote, always a ladder value; null until the first remote stream or probe decides it. */
	@Volatile var capBps: Int? = null
	@Volatile var probedBps: Long? = null
	@Volatile var probedAt: Long = 0L

	val probeIsFresh: Boolean
		get() = probedBps != null && System.currentTimeMillis() - probedAt < PROBE_TTL_MS

	/** The cap to ask the server for right now. */
	fun currentCapBps(): Int {
		capBps?.let { return it }
		val probe = probedBps?.takeIf { probeIsFresh } ?: return DEFAULT_REMOTE_BPS
		return ladderFloor((probe * PROBE_SAFETY).toLong())
	}
}

class AdaptiveBitrateController(
	private val state: AdaptiveBitrateState,
	private val userPreferences: UserPreferences,
	private val api: ApiClient,
) {
	private val handler = Handler(Looper.getMainLooper())
	private var controller: PlaybackController? = null
	private var ticker: Runnable? = null
	private var startedAt = 0L
	private var lastSwitchAt = 0L
	private var lastSwitchWasDown = false
	private var hasPlayed = false
	/** The cap we were forced to abandon, and when: do not climb back to it for a while. */
	private var failedCapBps = 0
	private var failedAt = 0L
	private var stallTicks = 0
	private var upTicks = 0
	private val aheadHistory = ArrayDeque<Long>()

	private val enabled: Boolean
		get() = userPreferences[UserPreferences.maxBitrate] == AUTO_QUALITY

	/** True while the ticker is watching a remote stream. */
	val isActive: Boolean
		get() = ticker != null

	/**
	 * Short readout for the bottom-right corner of the playback overlay, e.g. "Auto · 1080p · 5.6 Mb/s"
	 * for an adaptive transcode or "2160p · 24 Mb/s · Direct" for direct play. Resolution is what
	 * ExoPlayer is actually rendering; the bitrate is the video bitrate the server was asked for when
	 * transcoding, or the file's bitrate when playing directly.
	 */
	fun qualityLabel(c: PlaybackController): String {
		val info = c.currentStreamInfo ?: return ""
		val source = c.currentMediaSource
		val height = c.videoFormat?.height?.takeIf { it > 0 }
			?: source?.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }?.height
		val transcoding = info.playMethod == PlayMethod.TRANSCODE
		val bps: Long? = if (transcoding) {
			runCatching { Uri.parse(info.mediaUrl).getQueryParameter("VideoBitrate")?.toLong() }.getOrNull()
				?: state.capBps?.toLong()
		} else {
			source?.bitrate?.toLong()
		}
		val parts = mutableListOf<String>()
		if (isActive) parts += "Auto"
		height?.let { parts += "${it}p" }
		bps?.let { parts += mbitShort(it) }
		if (!transcoding) parts += "Direct"
		return parts.joinToString(" · ")
	}

	/**
	 * What the server is currently sending, in bits per second: the cap we asked it to transcode to,
	 * or the file's own bitrate when it is playing directly.
	 */
	private fun currentStreamBps(c: PlaybackController, cap: Int): Long =
		if (c.isTranscoding) cap.toLong() else (c.currentMediaSource?.bitrate?.toLong() ?: cap.toLong())

	/**
	 * Seconds of buffer lost per second of playback, averaged over the history window. Positive means
	 * emptying, negative means filling.
	 */
	private fun drainPerSecond(): Double? {
		if (aheadHistory.size < HISTORY) return null
		val windowSec = (aheadHistory.size - 1) * TICK_MS / 1000.0
		if (windowSec <= 0.0) return null
		return -(aheadHistory.last() - aheadHistory.first()) / 1000.0 / windowSec
	}

	/**
	 * Measures the link from the buffer rather than from ExoPlayer's throughput meter. Over a window
	 * of W seconds of playback the player consumed W seconds of media and received W + (change in
	 * buffer) seconds of it, so the link delivered stream_bitrate * (W + delta) / W.
	 *
	 * This is a direct measurement of what actually arrived. The throughput meter is a prediction and
	 * it is unreliable here: it read 1.8 and 159 Mbit/s on the same network minutes apart. It is only
	 * meaningful while the buffer is short of its cap, because a full buffer makes the player stop
	 * fetching and the figure then reads low - which is exactly the case we never use it for.
	 */
	private fun measuredLinkBps(streamBps: Long): Long? {
		val drain = drainPerSecond() ?: return null
		// Meaningless once the buffer is full, because the player stops fetching and the figure then
		// just echoes the stream's own bitrate. Callers must check that themselves.
		val delivered = streamBps * (1.0 - drain)
		return delivered.coerceAtLeast(0.0).toLong()
	}

	/** Measures the link to a remote server so the first stream starts at a sensible cap. */
	suspend fun probeIfNeeded() {
		if (!enabled || ServerLocality.isLocal(api, userPreferences) || state.probeIsFresh) return
		runCatching {
			var bps = measure(PROBE_SMALL_BYTES)
			// a fast link finishes the small sample too quickly to time well; take a bigger one
			if (bps > PROBE_FAST_LINK_BPS) bps = measure(PROBE_LARGE_BYTES)
			if (bps > PROBE_VERY_FAST_LINK_BPS) bps = measure(PROBE_HUGE_BYTES)
			state.probedBps = bps
			state.probedAt = System.currentTimeMillis()
			Timber.i(
				"Adaptive bitrate: link measured at %s, first stream will be capped at %s",
				mbit(bps), mbit(state.currentCapBps().toLong()),
			)
		}.onFailure { Timber.w(it, "Adaptive bitrate: link measurement failed") }
	}

	// The SDK's OkHttp client reads the body synchronously, so this must not run on the main thread.
	private suspend fun measure(size: Int): Long = withContext(Dispatchers.IO) {
		val started = System.nanoTime()
		val bytes = api.mediaInfoApi.getBitrateTestBytes(size).content.size
		val seconds = (System.nanoTime() - started) / 1_000_000_000.0
		(bytes * 8 / seconds).toLong()
	}

	/** Called by [PlaybackController] whenever a stream (re)starts. Safe to call repeatedly. */
	fun onStreamStarted(playbackController: PlaybackController) {
		stop()
		if (!enabled) return
		if (ServerLocality.isLocal(api, userPreferences)) {
			Timber.i("Adaptive bitrate: server is on the home network, playing at full quality")
			return
		}
		if (state.capBps == null) state.capBps = state.currentCapBps()
		controller = playbackController
		startedAt = now()
		hasPlayed = false
		stallTicks = 0
		upTicks = 0
		aheadHistory.clear()
		Timber.i(
			"Adaptive bitrate: watching the stream, cap %s (link probe %s)",
			mbit(state.currentCapBps().toLong()), mbit(state.probedBps ?: -1L),
		)
		ticker = object : Runnable {
			override fun run() {
				if (ticker !== this) return
				tick()
				if (ticker === this) handler.postDelayed(this, TICK_MS)
			}
		}.also { handler.postDelayed(it, TICK_MS) }
	}

	fun stop() {
		ticker?.let { handler.removeCallbacks(it) }
		ticker = null
		controller = null
	}

	private fun tick() {
		val c = controller ?: return
		val cap = state.capBps ?: return
		if (c.isPaused) {
			aheadHistory.clear()
			stallTicks = 0
			return
		}
		val position = c.currentPosition
		val buffered = c.bufferedPosition
		val duration = c.duration
		// PlaybackController reports the DURATION as the buffered position while the player is still
		// preparing, which would read as "buffered to the end". The real buffer is at most a few
		// minutes deep, so far from the end that value means "unknown" - treat it as empty so a
		// stream that cannot even start on this link counts as stalled.
		val bufferUnknown = duration > 0 && buffered >= duration - NEAR_END_MS && duration - position > BUFFER_FULL_MAX_MS
		val ahead = if (bufferUnknown) 0L else (buffered - position).coerceAtLeast(0L)
		val nearEnd = !bufferUnknown && duration > 0 && buffered >= duration - NEAR_END_MS
		val playing = c.isPlaying
		// A stream that has not begun playing yet is STARTING UP, not stalling: the player is not
		// playing and holds no buffer, which looks identical to a stall. Counting that as one made
		// every switch trigger the next one about 15 s later - the controller walked itself down to
		// the bottom rung on a fast home network (seen 2026-09-12). The warmup clock therefore
		// starts when playback actually begins, and nothing may step down before then.
		if (playing && !hasPlayed) {
			hasPlayed = true
			startedAt = now()
		}
		val sinceStart = now() - startedAt
		val sinceSwitch = now() - lastSwitchAt
		aheadHistory.addLast(ahead)
		if (aheadHistory.size > HISTORY) aheadHistory.removeFirst()

		// Stalled: it was playing, has now stopped, and has nothing buffered to play.
		stallTicks = if (hasPlayed && !playing && ahead < STALL_AHEAD_MS && !nearEnd) stallTicks + 1 else 0
		if (!hasPlayed || sinceStart < WARMUP_MS) return

		val tier = ladderIndex(cap)
		val streamBps = currentStreamBps(c, cap)
		val drain = drainPerSecond()
		val measuredLink = measuredLinkBps(streamBps)
		if (stallTicks >= STALL_TICKS && tier > 0 && sinceSwitch > STALL_COOLDOWN_MS) {
			// A stalled player has told us all we need: nothing is arriving. Drop a rung rather than
			// compute a target from a measurement taken while stopped.
			switchTo(
				LADDER_BPS[tier - 1],
				"the player stalled with %.1f s buffered".format(ahead / 1000.0),
				down = true,
			)
			return
		}
		// Buffer-driven step down. A buffer of 40 s falling fast is an emergency; the same 40 s
		// falling slowly is not, so the trigger is when it is PROJECTED to run out rather than a
		// fixed level, with an absolute floor underneath. The link is measured from the buffer
		// itself, which is a direct observation, and the target is chosen to fit it in one step.
		val emptyingMs = if (drain != null && drain > DRAIN_NOISE) (ahead / drain).toLong() else Long.MAX_VALUE
		// Only act on a buffer that is genuinely going down. Build 26 also fired when the buffer was
		// low but briefly refilling, and then computed the target from a measurement that is not valid
		// in that state, which read 19.6 Mbit/s on a 3 Mbit link and produced a cascade of small steps.
		val reallyDraining = drain != null && drain > DRAIN_NOISE
		val runningOut = reallyDraining && (ahead < BUFFER_FLOOR_MS || emptyingMs < ACT_BEFORE_EMPTY_MS)
		val nearlyGone = ahead < BUFFER_CRITICAL_MS && !nearEnd
		if (playing && (runningOut || nearlyGone) && tier > 0 && sinceSwitch > DOWN_COOLDOWN_MS) {
			// Trust the measurement only while draining; otherwise fall back to a single rung.
			val target = if (reallyDraining && measuredLink != null) {
				stepDownTarget(tier, measuredLink)
			} else {
				LADDER_BPS[tier - 1]
			}
			val why = when {
				reallyDraining && emptyingMs != Long.MAX_VALUE ->
					"%.0f s of buffer left, emptying in about %.0f s, link measures %s"
						.format(ahead / 1000.0, emptyingMs / 1000.0, mbit(measuredLink ?: 0L))
				reallyDraining ->
					"the buffer is draining and down to %.0f s, link measures %s".format(ahead / 1000.0, mbit(measuredLink ?: 0L))
				else ->
					"the buffer is nearly gone (%.0f s) with no usable measurement".format(ahead / 1000.0)
			}
			switchTo(target, why, down = true)
			return
		}
		// Climbing. Only while the server is actually transcoding: a direct-playing stream is already
		// the original file and no higher cap can improve it. There are two cases, and the throughput
		// meter is used in neither, because it reported 246.8 Mbit/s on a 3 Mbit link (2026-09-14).
		//
		//   buffer still FILLING -> the player is fetching flat out, so the measurement is real and we
		//                           can go straight to the rung it supports.
		//   buffer FULL          -> the player has stopped fetching, so nothing can be measured. All we
		//                           know is that the link beats the current stream, so take ONE rung.
		val next = LADDER_BPS.getOrNull(tier + 1)
		val bufferFull = ahead >= BUFFER_FULL_MS
		val filling = drain != null && drain < -DRAIN_NOISE
		val healthy = playing && c.isTranscoding && next != null && (ahead > UP_AHEAD_MS || nearEnd)
		if (healthy && (filling || bufferFull)) upTicks++ else upTicks = 0
		val upCooldown = if (lastSwitchWasDown) UP_AFTER_DOWN_MS else UP_COOLDOWN_MS
		if (next != null && upTicks >= UP_HOLD_TICKS && sinceSwitch > upCooldown) {
			// Climb only to what the link actually supports with headroom. maxOf(next, ...) used to
			// force a step up even when the sum said not to, which on 2026-09-14 took a 2.7 Mbit link
			// to a 3.0 Mbit stream and left nothing to refill the buffer: it dropped back 28 s later.
			val supported = if (filling && measuredLink != null) ladderFloor((measuredLink / UP_HEADROOM).toLong()) else null
			val target = supported ?: next
			val why = if (filling && measuredLink != null) {
				"the buffer has been filling for %d s and the link measures %s".format(UP_HOLD_TICKS, mbit(measuredLink))
			} else {
				"the buffer has sat full for %d s, so the link beats %s".format(UP_HOLD_TICKS, mbit(cap.toLong()))
			}
			// Climbing costs a stream swap, so only take a step big enough to be worth one. A
			// one-rung reservoir climb is exempt: it is how we discover headroom we cannot measure.
			// A rung we were just forced off is not worth retrying immediately; the reservoir climb in
			// particular is a guess, and without this it can oscillate on and off a marginal rung.
			val retryBlocked = failedCapBps > 0 && target >= failedCapBps && now() - failedAt < FAILED_RETRY_MS
			if (target > cap && !retryBlocked && (target >= cap * UP_MIN_GAIN || target == next)) {
				switchTo(target, why, down = false)
			}
		}
	}

	/** The rung to drop to: at least one step down, and low enough to fit the link we measured. */
	private fun stepDownTarget(tier: Int, linkBps: Long): Int {
		val below = LADDER_BPS[tier - 1]
		if (linkBps <= 0) return below
		return minOf(below, ladderFloor((linkBps * DOWN_SAFETY).toLong()))
	}

	private fun switchTo(target: Int, reason: String, down: Boolean) {
		val c = controller ?: return
		val cap = state.capBps ?: return
		if (target == cap) return
		Timber.i("Adaptive bitrate: %s -> %s because %s", mbit(cap.toLong()), mbit(target.toLong()), reason)
		if (down) {
			failedCapBps = cap
			failedAt = now()
		}
		state.capBps = target
		lastSwitchAt = now()
		lastSwitchWasDown = down
		upTicks = 0
		stallTicks = 0
		aheadHistory.clear()
		hasPlayed = false
		// Swaps to a stream at the new cap while the current one plays on out of its buffer, so the
		// change is not a visible restart; createDeviceProfile picks up the new cap.
		c.switchQualitySmoothly()
	}

	private fun now() = System.currentTimeMillis()
}
