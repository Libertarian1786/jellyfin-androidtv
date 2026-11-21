package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.preference.Preference

/**
 * HDR formats that the user can override in the device profile (upstream #5743).
 * Range types are the server's serial names because this SDK predates the 10.11 VideoRangeType values.
 */
enum class HdrFormat(
	val nameRes: Int,
	val rangeTypes: Set<String>,
	val preference: Preference<HdrOverrideMode>,
) {
	HDR10(R.string.hdr10_override, setOf("HDR10"), UserPreferences.hdr10Override),
	HDR10_PLUS(R.string.hdr10_plus_override, setOf("HDR10Plus"), UserPreferences.hdr10PlusOverride),
	DOVI_PROFILE_5(R.string.dovi_profile_5, setOf("DOVI"), UserPreferences.doviProfile5Override),
	DOVI_PROFILE_7(R.string.dovi_profile_7, setOf("DOVIWithEL", "DOVIWithELHDR10Plus"), UserPreferences.doviProfile7Override),
	DOVI_PROFILE_8(R.string.dovi_profile_8, setOf("DOVIWithHDR10", "DOVIWithHDR10Plus"), UserPreferences.doviProfile8Override),
}
