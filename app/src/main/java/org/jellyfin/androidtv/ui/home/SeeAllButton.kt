package org.jellyfin.androidtv.ui.home

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.GridButton
import java.util.UUID

/** Last card of a themed band row: opens the whole band, not just the handful the row shows. */
class SeeAllButton(val collectionId: UUID, val collectionName: String) :
	GridButton(ID, "See all", R.drawable.ic_grid) {
	companion object {
		const val ID = 9_001
	}
}
