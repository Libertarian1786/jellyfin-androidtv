package org.jellyfin.androidtv.ui.itemhandling

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Themed bands are shuffled, but only once a week (David 2026-11-19: titles moved around every time
 * he pressed Back). The order is a pure function of the item id and the week, so it is the same
 * after going back to home, after an app restart and on every TV; a newly added title slots in
 * without moving the others. Each Monday brings a new order.
 */
object WeeklyShuffle {
	private data class Cached(val week: Long, val ids: List<UUID>)

	private val cache = ConcurrentHashMap<UUID, Cached>()

	/** A themed band asks for a random order from a collection. */
	fun applies(query: GetItemsRequest) =
		query.parentId != null && query.sortBy?.contains(ItemSortBy.RANDOM) == true

	/** Monday-based week number (epoch day 0 was a Thursday). */
	private fun currentWeek() = Math.floorDiv(LocalDate.now().toEpochDay() + 3, 7)

	/** Every id in the band, in this week's order. Lists the band once per week per app run. */
	suspend fun order(api: ApiClient, collectionId: UUID): List<UUID> {
		val week = currentWeek()
		cache[collectionId]?.takeIf { it.week == week }?.let { return it.ids }

		val ids = api.itemsApi.getItems(
			GetItemsRequest(
				parentId = collectionId,
				fields = emptySet(),
				enableImages = false,
				enableUserData = false,
				enableTotalRecordCount = false,
			)
		).content.items.map { it.id }
		val ordered = ids.sortedBy { mix(it, week) }
		cache[collectionId] = Cached(week, ordered)
		return ordered
	}

	/** splitmix64 of the id and week: a stable pseudo-random sort key. */
	private fun mix(id: UUID, week: Long): Long {
		var z = id.mostSignificantBits xor id.leastSignificantBits xor (week * -0x61c8864680b583ebL)
		z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
		z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
		return z xor (z ushr 31)
	}
}
