package org.jellyfin.androidtv.ui.itemdetail

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import java.util.UUID

/**
 * Plays a show's or film's theme song quietly behind its details page, like the big streaming apps.
 * Fades in, loops, fades out when the page is left or playback starts (David 2026-11-21: "better experience").
 */
class ThemeMusicPlayer(private val context: Context, private val api: ApiClient) {
	private var player: MediaPlayer? = null
	private var playingFor: UUID? = null
	private var job: Job? = null
	private val handler = Handler(Looper.getMainLooper())
	private var volume = 0f

	fun play(owner: LifecycleOwner, item: BaseItemDto?) {
		if (item == null) return
		val sourceId = when (item.type) {
			BaseItemKind.SERIES, BaseItemKind.MOVIE -> item.id
			BaseItemKind.SEASON -> item.seriesId ?: item.id
			else -> null
		} ?: return stop()
		if (sourceId == playingFor && player != null) return
		stop()
		playingFor = sourceId
		job = owner.lifecycleScope.launch {
			val song = runCatching {
				withContext(Dispatchers.IO) {
					api.libraryApi.getThemeSongs(itemId = sourceId, inheritFromParent = true).content.items.firstOrNull()
				}
			}.onFailure { Timber.w(it, "Theme song lookup failed") }.getOrNull() ?: return@launch
			if (playingFor != sourceId) return@launch
			start(song.id)
		}
	}

	private fun start(songId: UUID) {
		val url = api.audioApi.getAudioStreamUrl(itemId = songId, static = true)
		val headers = mapOf("Authorization" to "MediaBrowser Token=\"${api.accessToken}\"")
		runCatching {
			player = MediaPlayer().apply {
				setAudioAttributes(
					AudioAttributes.Builder()
						.setUsage(AudioAttributes.USAGE_MEDIA)
						.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
						.build()
				)
				setDataSource(context, Uri.parse(url), headers)
				isLooping = true
				setVolume(0f, 0f)
				setOnPreparedListener { mp ->
					if (player !== mp) return@setOnPreparedListener
					mp.start()
					fade(mp, 0f, MAX_VOLUME)
				}
				setOnErrorListener { _, what, extra ->
					Timber.w("Theme song error %d/%d", what, extra)
					stop()
					true
				}
				prepareAsync()
			}
		}.onFailure { Timber.w(it, "Theme song could not start"); stop() }
	}

	/** Ramps one player's volume; each player has its own ramp so a fade-out never touches the next song. */
	private fun fade(mp: MediaPlayer, from: Float, target: Float, then: (() -> Unit)? = null) {
		val step = if (target > from) 0.02f else -0.05f
		var v = from
		val tick = object : Runnable {
			override fun run() {
				v = if (step > 0) minOf(target, v + step) else maxOf(target, v + step)
				if (runCatching { mp.setVolume(v, v) }.isFailure) {
					then?.invoke()
					return
				}
				if (step > 0) volume = v
				if (v != target) handler.postDelayed(this, 60) else then?.invoke()
			}
		}
		handler.post(tick)
	}

	/** Fades out and releases. Safe to call any time. */
	fun stop() {
		job?.cancel(); job = null
		playingFor = null
		val mp = player ?: return
		player = null
		val from = volume
		volume = 0f
		fade(mp, from, 0f) {
			runCatching { mp.stop() }
			runCatching { mp.release() }
		}
	}

	private companion object {
		const val MAX_VOLUME = 0.35f
	}
}
