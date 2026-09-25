package org.jellyfin.androidtv.ui.playback

import android.os.Handler
import android.os.Looper
import org.koin.java.KoinJavaComponent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Player sleep timer: stop after the current episode/film, or after a number of minutes.
 * Lives for the app process; cleared when it fires or is turned off.
 */
object SleepTimer {
	private val handler = Handler(Looper.getMainLooper())
	private val fire = Runnable {
		cancel()
		KoinJavaComponent.get<PlaybackControllerContainer>(PlaybackControllerContainer::class.java)
			.playbackController?.endPlayback(true)
	}

	@JvmStatic
	var endOfItem: Boolean = false
		private set

	/** Uptime millis when the timed stop fires, or null. */
	private var firesAt: Long? = null

	val options: List<Duration> = listOf(30.minutes, 60.minutes, 90.minutes)

	@JvmStatic
	fun minutesLeft(): Long? = firesAt?.let { ((it - android.os.SystemClock.uptimeMillis()) / 60_000L).coerceAtLeast(0) }

	@JvmStatic
	val isOn: Boolean get() = endOfItem || firesAt != null

	fun stopAfter(duration: Duration) {
		cancel()
		firesAt = android.os.SystemClock.uptimeMillis() + duration.inWholeMilliseconds
		handler.postDelayed(fire, duration.inWholeMilliseconds)
	}

	fun stopAtEndOfItem() {
		cancel()
		endOfItem = true
	}

	@JvmStatic
	fun cancel() {
		handler.removeCallbacks(fire)
		firesAt = null
		endOfItem = false
	}

	/** Called when an item finishes; true = stop here instead of playing the next one. */
	@JvmStatic
	fun consumeEndOfItem(): Boolean {
		if (!endOfItem) return false
		cancel()
		return true
	}
}
