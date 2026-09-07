package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

enum class ZoomMode(
	override val nameRes: Int,
) : PreferenceEnum {
	/**
	 * Sets the zoom mode to normal (fit).
	 */
	FIT(R.string.lbl_fit),

	/**
	 * Sets the zoom mode to auto crop.
	 */
	AUTO_CROP(R.string.lbl_auto_crop),

	/**
	 * Sets the zoom mode to stretch.
	 */
	STRETCH(R.string.lbl_stretch),

	/**
	 * Enlarges the picture 7%: removes the side bars of a 1.66:1 film delivered inside a 16:9 frame
	 * (most Disney animation), which the resize modes above cannot see because the frame is 16:9.
	 */
	ZOOM_107(R.string.lbl_zoom_107),

	/**
	 * Enlarges the picture 20%.
	 */
	ZOOM_120(R.string.lbl_zoom_120),

	/**
	 * Enlarges the picture 35%: removes the top and bottom bars of a 2.40:1 film delivered inside a 16:9 frame.
	 */
	ZOOM_135(R.string.lbl_zoom_135),
}

