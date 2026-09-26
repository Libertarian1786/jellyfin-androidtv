package org.jellyfin.androidtv.util

import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * Libraries meant for the iPad only (Travel Mix: 50 random episodes reshuffled daily for Streamyfin
 * downloads). The TVs share David's account, so the TV app hides them everywhere.
 */
object IpadOnly {
	private val names = setOf("Travel Mix")

	@JvmStatic
	fun hides(item: BaseItemDto): Boolean =
		item.name in names || item.seriesName in names || item.album in names

	@JvmStatic
	fun hides(item: Any?): Boolean = item is BaseItemDto && hides(item)
}
