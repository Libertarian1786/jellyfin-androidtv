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

	/**
	 * Widens the picture 7% without touching its height: fills the side bars of a 1.66:1 film
	 * delivered in a 16:9 frame while keeping the full picture (slightly wider faces instead of a crop).
	 */
	STRETCH_WIDE_107(R.string.lbl_stretch_wide_107),

	/**
	 * Widens the picture 33% without touching its height: a 4:3 picture fills a 16:9 screen with
	 * nothing cropped top or bottom. Works for a real 4:3 file (which the player would otherwise
	 * pillarbox) and for 4:3 painted inside a 16:9 frame.
	 */
	STRETCH_WIDE_133(R.string.lbl_stretch_wide_133),
}

