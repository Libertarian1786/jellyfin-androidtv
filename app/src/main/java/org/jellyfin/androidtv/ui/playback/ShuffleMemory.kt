package org.jellyfin.androidtv.ui.playback

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * Remembers which shows were last started with Shuffle, per TV. Without it, stopping a shuffled
 * episode and resuming it later from Continue Watching queued the rest of the show in order.
 * Shuffle on the show or season page turns it on; Play / Play all there turns it off.
 */
object ShuffleMemory {
	private const val PREFS = "shuffle_memory"

	@JvmStatic
	fun isOn(context: Context, seriesId: UUID): Boolean =
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(seriesId.toString(), false)

	@JvmStatic
	fun set(context: Context, seriesId: UUID, on: Boolean) {
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
			if (on) putBoolean(seriesId.toString(), true) else remove(seriesId.toString())
		}
	}
}
