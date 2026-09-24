package org.jellyfin.androidtv.ui.playback

import android.content.Context
import androidx.core.content.edit
import org.jellyfin.androidtv.preference.constant.ZoomMode
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * Remembers the zoom the viewer picked, per title: episodes share their show's setting (set
 * stretch once on Star Trek TNG and every episode opens stretched), anything else keys on itself.
 * Picking Fit forgets it. Stored per device, like the other player preferences.
 */
object TitleZoomMemory {
	private const val PREFS = "title_zoom_memory"

	private fun key(item: BaseItemDto): String = (item.seriesId ?: item.id).toString()

	@JvmStatic
	fun get(context: Context, item: BaseItemDto): ZoomMode? {
		val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(item), null)
		return ZoomMode.entries.firstOrNull { it.name == name }
	}

	@JvmStatic
	fun set(context: Context, item: BaseItemDto, mode: ZoomMode) {
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
			if (mode == ZoomMode.FIT) remove(key(item)) else putString(key(item), mode.name)
		}
	}
}
