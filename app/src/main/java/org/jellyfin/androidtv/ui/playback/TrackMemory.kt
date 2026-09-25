package org.jellyfin.androidtv.ui.playback

import android.content.Context
import androidx.core.content.edit
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * Remembers the audio language and subtitle choice picked in the player, per show (episodes share
 * their series) or per film, on this TV. Subtitles still start off everywhere else; a show only
 * starts with subtitles when the viewer turned them on for it.
 */
object TrackMemory {
	private const val PREFS = "track_memory"
	private const val OFF = "off"

	private fun key(item: BaseItemDto, kind: String) = "${item.seriesId ?: item.id}:$kind"

	/** Language code, or the track title when the file doesn't tag a language. */
	private fun label(stream: MediaStream) = stream.language?.takeIf { it.isNotBlank() } ?: "title:${stream.displayTitle}"

	private fun matches(stream: MediaStream, label: String) =
		stream.language == label || "title:${stream.displayTitle}" == label

	@JvmStatic
	fun saveAudio(context: Context, item: BaseItemDto, stream: MediaStream) =
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(key(item, "audio"), label(stream)) }

	/** [stream] null = subtitles turned off. */
	@JvmStatic
	fun saveSubtitle(context: Context, item: BaseItemDto, stream: MediaStream?) =
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
			if (stream == null) remove(key(item, "subtitle")) else putString(key(item, "subtitle"), label(stream))
		}

	@JvmStatic
	fun audioIndex(context: Context, item: BaseItemDto, streams: List<MediaStream>?): Int? {
		val label = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(item, "audio"), null) ?: return null
		return streams?.firstOrNull { it.type == MediaStreamType.AUDIO && matches(it, label) }?.index
	}

	@JvmStatic
	fun subtitleIndex(context: Context, item: BaseItemDto, streams: List<MediaStream>?): Int? {
		val label = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(item, "subtitle"), null) ?: return null
		if (label == OFF) return null
		val subs = streams?.filter { it.type == MediaStreamType.SUBTITLE && matches(it, label) }.orEmpty()
		// Prefer a full track over a forced-only one
		return (subs.firstOrNull { !it.isForced } ?: subs.firstOrNull())?.index
	}
}
