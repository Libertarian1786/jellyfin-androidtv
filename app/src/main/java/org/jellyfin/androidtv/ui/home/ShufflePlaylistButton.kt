package org.jellyfin.androidtv.ui.home

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.GridButton

/**
 * First card of a band row that has a matching server playlist (Christmas TV -> "Christmas TV
 * Shuffle", built by the SmartLists plugin so each episode is followed by a different show).
 * Pressing it plays that playlist in its own order.
 */
class ShufflePlaylistButton(val playlistName: String) :
	GridButton(ID, "Shuffle", R.drawable.ic_shuffle) {
	companion object {
		const val ID = 9_002

		/** Band name -> playlist its Shuffle card plays (David 2026-11-19). */
		val PLAYLIST_FOR_BAND = mapOf("Christmas TV" to "Christmas TV Shuffle")
	}
}
