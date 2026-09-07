package org.jellyfin.androidtv.ui.playback

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.constant.AUTO_QUALITY
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import timber.log.Timber
import java.net.InetAddress
import java.net.URI

/*
 * Adaptive bitrate for the "Auto" quality setting.
 *
 * Jellyfin sends a single stream at the bitrate the client asks for, so there is no ladder for the
 * player to switch between on its own. Instead the controller below watches the buffer and
 * ExoPlayer's throughput estimate once a second and, when the buffer is emptying faster than it
 * fills (or the player has actually stalled), lowers the cap and restarts the stream from the
 * current position - the same thing the quality picker does by hand. After the link has been
 * comfortably faster than the next step for a while it climbs back up, one step at a time.
 *
 * It only runs when the server is reached over the internet; on the home network there is no cap.
 */

/** Full quality: the cap used on the home network, in bits per second. */
const val AUTO_LOCAL_BPS = 200_000_000

/** The steps used while remote, in bits per second. */
private val LADDER_BPS = listOf(
	1_500_000, 2_000_000, 3_000_000, 4_000_000, 5_000_000, 6_000_000,
	8_000_000, 10_000_000, 12_000_000, 15_000_000, 20_000_000,
)
private const val DEFAULT_REMOTE_BPS = 5_000_000
private const val PROBE_TTL_MS = 10 * 60 * 1000L
private const val PROBE_SMALL_BYTES = 500_000
private const val PROBE_LARGE_BYTES = 2_000_000
private const val PROBE_FAST_LINK_BPS = 4_000_000
private const val PROBE_SAFETY = 0.8

private const val TICK_MS = 1_000L
private const val HISTORY = 10
private const val WARMUP_MS = 15_000L
private const val STALL_TICKS = 3
private const val STALL_AHEAD_MS = 1_500L
private const val STALL_COOLDOWN_MS = 10_000L
private const val DRAIN_AHEAD_MS = 15_000L
private const val DRAIN_DROP_MS = 3_000L
private const val DRAIN_LINK_HEADROOM = 1.25
private const val DOWN_COOLDOWN_MS = 20_000L
private const val DOWN_SAFETY = 0.7
private const val UP_AHEAD_MS = 40_000L
private const val UP_HEADROOM = 1.6
private const val UP_HOLD_TICKS = 60
private const val UP_COOLDOWN_MS = 120_000L
private const val UP_AFTER_DOWN_MS = 180_000L
private const val NEAR_END_MS = 2_000L

private fun ladderFloor(bps: Long): Int = LADDER_BPS.lastOrNull { it <= bps } ?: LADDER_BPS.first()
private fun ladderIndex(bps: Int): Int = LADDER_BPS.indexOfFirst { it >= bps }.takeIf { it >= 0 } ?: LADDER_BPS.lastIndex
private fun mbit(bps: Long): String = if (bps <= 0) "unknown" else "%.1f Mbit/s".format(bps / 1_000_000.0)

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
	private var stallTicks = 0
	private var upTicks = 0
	private val aheadHistory = ArrayDeque<Long>()

	private val enabled: Boolean
		get() = userPreferences[UserPreferences.maxBitrate] == AUTO_QUALITY

	/** Measures the link to a remote server so the first stream starts at a sensible cap. */
	suspend fun probeIfNeeded() {
		if (!enabled || ServerLocality.isLocal(api, userPreferences) || state.probeIsFresh) return
		runCatching {
			var bps = measure(PROBE_SMALL_BYTES)
			// a fast link finishes the small sample too quickly to time well; take a bigger one
			if (bps > PROBE_FAST_LINK_BPS) bps = measure(PROBE_LARGE_BYTES)
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
		val ahead = (buffered - position).coerceAtLeast(0L)
		val nearEnd = duration > 0 && buffered >= duration - NEAR_END_MS
		val estimate = c.bandwidthEstimate
		val playing = c.isPlaying
		val sinceStart = now() - startedAt
		val sinceSwitch = now() - lastSwitchAt
		aheadHistory.addLast(ahead)
		if (aheadHistory.size > HISTORY) aheadHistory.removeFirst()

		// Stalled: not paused, not playing, and nothing buffered to play.
		if (!playing && ahead < STALL_AHEAD_MS && !nearEnd) stallTicks++ else stallTicks = 0
		if (sinceStart < WARMUP_MS) return

		val tier = ladderIndex(cap)
		if (stallTicks >= STALL_TICKS && tier > 0 && sinceSwitch > STALL_COOLDOWN_MS) {
			switchTo(
				stepDownTarget(tier, estimate),
				"the player stalled (buffer %.1f s, link %s)".format(ahead / 1000.0, mbit(estimate)),
				down = true,
			)
			return
		}
		val draining = aheadHistory.size >= HISTORY && ahead < DRAIN_AHEAD_MS && aheadHistory.first() - ahead >= DRAIN_DROP_MS
		val linkNotAhead = estimate <= 0 || estimate < cap * DRAIN_LINK_HEADROOM
		if (playing && draining && linkNotAhead && tier > 0 && sinceSwitch > DOWN_COOLDOWN_MS) {
			switchTo(
				stepDownTarget(tier, estimate),
				"the buffer is draining (%.1f s left, link %s)".format(ahead / 1000.0, mbit(estimate)),
				down = true,
			)
			return
		}
		val next = LADDER_BPS.getOrNull(tier + 1)
		val roomToClimb = playing && next != null && (ahead > UP_AHEAD_MS || nearEnd) && estimate > next * UP_HEADROOM
		if (roomToClimb) upTicks++ else upTicks = 0
		val upCooldown = if (lastSwitchWasDown) UP_AFTER_DOWN_MS else UP_COOLDOWN_MS
		if (next != null && upTicks >= UP_HOLD_TICKS && sinceSwitch > upCooldown) {
			switchTo(
				next,
				"the link has held %s for %d s (buffer %.1f s)".format(mbit(estimate), UP_HOLD_TICKS, ahead / 1000.0),
				down = false,
			)
		}
	}

	private fun stepDownTarget(tier: Int, estimate: Long): Int {
		val below = LADDER_BPS[tier - 1]
		if (estimate <= 0) return below
		return minOf(below, ladderFloor((estimate * DOWN_SAFETY).toLong()))
	}

	private fun switchTo(target: Int, reason: String, down: Boolean) {
		val c = controller ?: return
		val cap = state.capBps ?: return
		if (target == cap) return
		Timber.i("Adaptive bitrate: %s -> %s because %s", mbit(cap.toLong()), mbit(target.toLong()), reason)
		state.capBps = target
		lastSwitchAt = now()
		lastSwitchWasDown = down
		upTicks = 0
		stallTicks = 0
		aheadHistory.clear()
		// Restarts the stream from the current position; createDeviceProfile picks up the new cap.
		c.refreshStream()
	}

	private fun now() = System.currentTimeMillis()
}
