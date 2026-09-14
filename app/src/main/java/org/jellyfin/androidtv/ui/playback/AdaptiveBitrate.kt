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

/**
 * Treat the buffer as full, and therefore uninformative, at this depth - it is where the player
 * stops fetching (VideoManager's minBufferMs), so nothing above it measures the link.
 */
private const val BUFFER_FULL_MS = 150_000L

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

/**
 * How far ahead of the current position a pre-buffered replacement is told to begin - and so how
 * long it has to arrive in. Jellyfin takes 10-15 s just to start the new encode (timed from its
 * own transcode logs on 2026-09-14), and whatever is left of this after that is all the time the
 * replacement gets: at 20 s the two measured cross-overs got 9 s and 3 s of fetching, and were
 * seamless and 5.7 s of silence respectively.
 */
private const val CROSSOVER_LEAD_MS = 45_000L

/**
 * Bytes the pre-fetch may spend. Preloading happens outside the player's allocator cap, so the
 * budget is in bytes rather than seconds: 45 s of a 2 Mbit/s stream is 11 MB and welcome, 45 s of
 * a 200 Mbit/s stream is 1.1 GB and would take the device down.
 */
private const val PRELOAD_BUDGET_BYTES = 40_000_000L
/**
 * Hand over only once this much of the replacement has actually arrived. Below it the hand-over
 * is seen: build 35 crossed over with about 14 s in hand and cost 5.7 s of silence, and crossed
 * over with 43 s in hand and cost nothing at all.
 */
private const val CROSSOVER_READY_MS = 20_000L

/** How many times to re-queue further ahead before giving up and taking the restarting swap. */
private const val CROSSOVER_MAX_ATTEMPTS = 3
/** Give up waiting for the replacement after this and fall back to the restarting swap. */
private const val CROSSOVER_TIMEOUT_MS = 90_000L

/**
 * Only pre-buffer a replacement when the buffer is at least this deep. Pre-buffering pays for the
 * same seconds of film twice, which is only affordable out of genuine spare capacity.
 */
private const val CROSSOVER_MIN_BUFFER_MS = 60_000L

/**
 * The same rule for a step DOWN. The playhead reaches the hand-over point CROSSOVER_LEAD_MS later
 * whatever we do, so the buffer has to still cover that point when it gets there; below this there
 * is nothing to play out of and a restart is the honest answer.
 */
private const val CROSSOVER_MIN_BUFFER_DOWN_MS = 60_000L

/**
 * Upper edge for the early step down. DefaultLoadControl stops fetching at maxBufferMs and does
 * not resume until minBufferMs, and in between the link is deliberately idle while the buffer
 * drains at a full second per second. A drain measured up there is not evidence about the link at
 * all, so the early trigger stays clear of it. (VideoManager: 150 s / 180 s.)
 */
private const val CROSSOVER_MAX_BUFFER_DOWN_MS = 130_000L

/** How long a link measurement is worth remembering when judging a climb we cannot measure. */
private const val LINK_MEMORY_MS = 300_000L

/** Seconds of continuous draining before the early step down fires, so a brief dip cannot. */
private const val DRAIN_HOLD_TICKS = 15

/**
 * After a cross-over the new stream holds only what was pre-buffered, so a shallow buffer is
 * expected rather than alarming. Nothing but a real stall may act during this.
 */
private const val CROSSOVER_SETTLE_MS = 60_000L
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
	/** Set while a replacement stream is queued and pre-buffering, with the cap it was queued for. */
	private var crossingTo = 0
	private var crossingSince = 0L
	/** The cap we were forced to abandon, and when: do not climb back to it for a while. */
	private var failedCapBps = 0
	private var failedAt = 0L
	private var stallTicks = 0
	private var upTicks = 0
	private val aheadHistory = ArrayDeque<Long>()
	private var lastAhead = 0L
	/** The best link we have actually measured lately - the ceiling for a climb taken blind. */
	private var bestLinkBps = 0L
	private var bestLinkAt = 0L
	/** Consecutive ticks the buffer has been genuinely going down. */
	private var drainTicks = 0
	/** Re-queue attempts made for the change-over in flight, and the pre-fetch budget it uses. */
	private var crossAttempts = 0
	private var crossPreloadMs = 0L
	/** How long after a (re)start the buffer may be shallow without anyone acting on it. */
	private var settleMs = WARMUP_MS

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
		settleMs = WARMUP_MS
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

	/**
	 * Called instead of [onStreamStarted] when the new stream arrived by cross-over. Same reset, but
	 * with a longer settle window: playback never stopped, so the only thing that is new is a buffer
	 * that starts at whatever was pre-buffered and needs time to fill.
	 */
	fun onCrossOverCompleted(playbackController: PlaybackController) {
		onStreamStarted(playbackController)
		settleMs = CROSSOVER_SETTLE_MS
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
		lastAhead = ahead

		// Stalled: it was playing, has now stopped, and has nothing buffered to play.
		stallTicks = if (hasPlayed && !playing && ahead < STALL_AHEAD_MS && !nearEnd) stallTicks + 1 else 0
		// A cross-over in flight owns the tick: watch it, and do not start another change.
		if (crossingTo > 0) {
			val queuedStart = c.queuedStartMs
			val queuedBuffered = c.queuedBufferedMs
			val waited = now() - crossingSince
			when {
				// Safety valve: while a change-over is in flight nothing else in the tick runs, so a
				// buffer that collapses underneath it would go unanswered. Give up and take the swap.
				ahead < BUFFER_CRITICAL_MS && !nearEnd -> {
					Timber.w("Adaptive bitrate: the buffer collapsed to %.0f s mid change-over, falling back to a restart", ahead / 1000.0)
					state.capBps = crossingTo
					crossingTo = 0
					c.abandonCrossOver()
					c.switchQualitySmoothly()
					return
				}
				queuedStart < 0 && waited > CROSSOVER_TIMEOUT_MS -> {
					Timber.w("Adaptive bitrate: the replacement never arrived, falling back to a restart")
					state.capBps = crossingTo
					crossingTo = 0
					c.abandonCrossOver()
					c.switchQualitySmoothly()
					return
				}
				// Cross over when playback REACHES the queued start. Do not gate on how much of the
				// replacement appears buffered: the preload pool is separate from the playlist, so
				// getTotalBufferedDuration cannot see it, and waiting on that figure made every
				// cross-over time out (builds 29 and 30) whether or not preloading had worked.
				queuedStart >= 0 && c.currentPosition >= queuedStart - TICK_MS -> {
					// Measure readiness in bytes: the player's media-time figure for a preloaded item
					// under-reports badly (5.4 s for 3627 kB of a 2.0 Mbit/s stream, and 0.0 s once).
					val arrivedMs = maxOf(queuedBuffered, c.queuedBytes * 8_000 / crossingTo)
					if (arrivedMs < CROSSOVER_READY_MS && crossAttempts < CROSSOVER_MAX_ATTEMPTS &&
						ahead >= CROSSOVER_MIN_BUFFER_DOWN_MS
					) {
						// Not enough of it is here yet. The stream we are on is still playing and still
						// healthy, so nothing forces the change now: drop this attempt and queue another
						// further ahead. Handing over to a starved stream costs its own gap AND the buffer
						// every later decision depends on.
						crossAttempts++
						Timber.i(
							"Adaptive bitrate: only %.0f s of the %s stream has arrived, trying again further ahead (%d/%d)",
							arrivedMs / 1000.0, mbit(crossingTo.toLong()), crossAttempts, CROSSOVER_MAX_ATTEMPTS,
						)
						c.abandonCrossOver()
						crossingSince = now()
						c.beginCrossOver(CROSSOVER_LEAD_MS, crossPreloadMs)
						return
					}
					Timber.i(
						"Adaptive bitrate: crossing over to %s at %.1f s (%.0f s of it in hand, %d kB)",
						mbit(crossingTo.toLong()), queuedStart / 1000.0, arrivedMs / 1000.0, c.queuedBytes / 1024,
					)
					state.capBps = crossingTo
					crossingTo = 0
					if (!c.completeCrossOver()) c.switchQualitySmoothly()
					return
				}
				waited > CROSSOVER_TIMEOUT_MS -> {
					Timber.w("Adaptive bitrate: the replacement did not buffer in time, falling back to a restart")
					state.capBps = crossingTo
					crossingTo = 0
					c.abandonCrossOver()
					c.switchQualitySmoothly()
					return
				}
				else -> return
			}
		}
		if (!hasPlayed) return

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
		// A stream that has just started - or has just been crossed over to - holds only a shallow
		// buffer, and that is normal rather than a warning. Build 31 had no such window and stepped
		// straight back down 16 s after a cross-over on a buffer that was simply still filling. But
		// build 32 made it a blanket silence, and when a climb HAD overshot the link the buffer ran
		// from full to 4 s inside that window, so a buffer that is genuinely emptying still gets
		// through. A stream that fits is filling, and never trips this.
		val settling = sinceStart < settleMs
		// Buffer-driven step down. A buffer of 40 s falling fast is an emergency; the same 40 s
		// falling slowly is not, so the trigger is when it is PROJECTED to run out rather than a
		// fixed level, with an absolute floor underneath. The link is measured from the buffer
		// itself, which is a direct observation, and the target is chosen to fit it in one step.
		val emptyingMs = if (drain != null && drain > DRAIN_NOISE) (ahead / drain).toLong() else Long.MAX_VALUE
		// Only act on a buffer that is genuinely going down. Build 26 also fired when the buffer was
		// low but briefly refilling, and then computed the target from a measurement that is not valid
		// in that state, which read 19.6 Mbit/s on a 3 Mbit link and produced a cascade of small steps.
		val reallyDraining = drain != null && drain > DRAIN_NOISE
		drainTicks = if (reallyDraining) drainTicks + 1 else 0
		val runningOut = reallyDraining && (ahead < BUFFER_FLOOR_MS || emptyingMs < ACT_BEFORE_EMPTY_MS)
		val nearlyGone = ahead < BUFFER_CRITICAL_MS && !nearEnd
		// Deep enough to hand over without the picture noticing, and the change would be smooth.
		// Acting here rather than at the last responsible moment is the whole point: the late
		// triggers below fire with 20 s or 8 s in hand, which is not enough to cross over with.
		val deepEnough = reallyDraining && ahead >= CROSSOVER_MIN_BUFFER_DOWN_MS &&
			ahead <= CROSSOVER_MAX_BUFFER_DOWN_MS
		// No projection test here, deliberately. "Will it run out within N seconds" was how this
		// worked when every change cost a restart and the question was how long we could put one off.
		// It also makes the trigger unreachable: on a gentle drain the projection is not met until the
		// buffer is far below what a change-over needs, and on a steep one it is met almost at once.
		// A buffer that has fallen steadily for DRAIN_HOLD_TICKS seconds already says what matters -
		// the link is not carrying this stream - and a smooth change is cheap enough to act on that.
		val smoothDown = deepEnough && drainTicks >= DRAIN_HOLD_TICKS &&
			userPreferences[UserPreferences.adaptivePreloadSwitch]
		val settledEnough = !settling || (reallyDraining && ahead < BUFFER_CRITICAL_MS)
		if (playing && settledEnough && (runningOut || nearlyGone || smoothDown) && tier > 0 && sinceSwitch > DOWN_COOLDOWN_MS) {
			// Trust the measurement only while draining; otherwise fall back to a single rung.
			val target = if (reallyDraining && measuredLink != null) {
				stepDownTarget(tier, measuredLink)
			} else {
				LADDER_BPS[tier - 1]
			}
			val why = when {
				smoothDown && !runningOut && !nearlyGone ->
					"the buffer has been draining for %d s, %.0f s left and emptying in about %.0f s, link measures %s"
						.format(drainTicks, ahead / 1000.0, emptyingMs / 1000.0, mbit(measuredLink ?: 0L))
				reallyDraining && emptyingMs != Long.MAX_VALUE ->
					"%.0f s of buffer left, emptying in about %.0f s, link measures %s"
						.format(ahead / 1000.0, emptyingMs / 1000.0, mbit(measuredLink ?: 0L))
				reallyDraining ->
					"the buffer is draining and down to %.0f s, link measures %s".format(ahead / 1000.0, mbit(measuredLink ?: 0L))
				else ->
					"the buffer is nearly gone (%.0f s) with no usable measurement".format(ahead / 1000.0)
			}
			// Only a deep buffer can survive a cross-over on the way down, and only the projection
			// branch fires while the buffer is still deep. The floor, critical and stall branches fire
			// at or under the lead, where the playhead would reach the hand-over point with nothing
			// left to show; those keep the restart.
			switchTo(target, why, down = true, mayCrossOver = deepEnough)
			return
		}
		if (settling) return
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
		// A measurement only means anything while the player is fetching flat out. Remember the best
		// one: it is the only evidence available to a climb taken from a full buffer. Let it expire,
		// or a link that has since got worse would be judged for ever against how good it once was.
		if (bestLinkBps > 0 && now() - bestLinkAt >= LINK_MEMORY_MS) bestLinkBps = 0
		if (measuredLink != null && (filling || reallyDraining) && measuredLink > bestLinkBps) {
			bestLinkBps = measuredLink
			bestLinkAt = now()
		}
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
			// A full buffer proves the link beats the stream we are already asking for, and nothing
			// more. On 2026-09-14 that blind one-rung climb took a 3.15 Mbit link to the 3.0 Mbit rung -
			// 3.0 of video plus 0.256 of audio, more than the link carries - and the bad guess cost 29 s
			// of silence before it fell back. So it may not aim above what our best real measurement
			// supports with the same headroom every other climb has to clear.
			val recentBest = bestLinkBps
			val blindTooHigh = supported == null && recentBest > 0 && target * UP_HEADROOM > recentBest
			if (target > cap && !retryBlocked && !blindTooHigh && (target >= cap * UP_MIN_GAIN || target == next)) {
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

	private fun switchTo(target: Int, reason: String, down: Boolean, mayCrossOver: Boolean = !down) {
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
		drainTicks = 0
		aheadHistory.clear()
		hasPlayed = false
		// Cross over in EITHER direction, but only out of a buffer deep enough to play out of while
		// the replacement is fetched. The playhead reaches the hand-over point CROSSOVER_LEAD_MS after
		// queueing whatever the link is doing, so the requirement is on the buffer, not on the
		// direction. Build 32 refused every downward cross-over; it was right that a step down taken
		// at 8 s of buffer cannot survive one, and wrong that a step down taken at 60 s cannot.
		val minBuffer = if (down) CROSSOVER_MIN_BUFFER_DOWN_MS else CROSSOVER_MIN_BUFFER_MS
		if (mayCrossOver && lastAhead >= minBuffer && userPreferences[UserPreferences.adaptivePreloadSwitch]) {
			// Queue the new quality to begin a little ahead of here and let the player pre-buffer it
			// while this stream carries on, then cross over when playback reaches that point. The
			// tick loop above drives the rest; state.capBps only moves when the cross-over lands, so
			// the profile keeps describing the stream that is actually playing until then.
			crossingTo = target
			crossingSince = now()
			// state.capBps stays at the target: createDeviceProfile reads it when the request is
			// built, so restoring the old value here would fetch the replacement at the old bitrate.
			// Pre-fetch for as long as the lead allows, within the byte budget for this bitrate.
			crossPreloadMs = minOf(CROSSOVER_LEAD_MS, PRELOAD_BUDGET_BYTES * 8_000 / target.toLong())
			crossAttempts = 0
			c.beginCrossOver(CROSSOVER_LEAD_MS, crossPreloadMs)
			return
		}
		// Swaps to a stream at the new cap while the current one plays on out of its buffer, so the
		// change is not a visible restart; createDeviceProfile picks up the new cap.
		c.switchQualitySmoothly()
	}

	private fun now() = System.currentTimeMillis()
}
