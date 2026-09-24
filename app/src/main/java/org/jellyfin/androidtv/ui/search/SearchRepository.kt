package org.jellyfin.androidtv.ui.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.text.Normalizer
import java.util.UUID
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource

interface SearchRepository {
	suspend fun search(
		searchTerm: String,
		itemTypes: Collection<BaseItemKind>,
	): Result<List<BaseItemDto>>
}

class SearchRepositoryImpl(
	private val apiClient: ApiClient
) : SearchRepository {
	companion object {
		private const val QUERY_LIMIT = 25

		/** Kinds searched forgivingly: punctuation, spacing and word order don't matter. */
		private val FORGIVING_KINDS = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
		private val INDEX_MAX_AGE = 1.hours

		private val nonAlphanumeric = Regex("[^a-z0-9]+")
		private val diacritics = Regex("\\p{Mn}+")

		/** "Joe's College: Road-Trip" -> "joescollegeroadtrip". */
		fun compact(text: String): String = words(text).joinToString("")

		fun words(text: String): List<String> = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
			.replace(diacritics, "")
			.replace("&", " and ")
			.split(nonAlphanumeric)
			.filter { it.isNotEmpty() }
	}

	private class TitleEntry(val id: UUID, val compactNames: List<String>, val sortName: String)

	private class TitleIndex(val entries: List<TitleEntry>, val builtAt: TimeSource.Monotonic.ValueTimeMark)

	private val indexMutex = Mutex()
	private val indexes = mutableMapOf<BaseItemKind, TitleIndex>()

	override suspend fun search(
		searchTerm: String,
		itemTypes: Collection<BaseItemKind>,
	): Result<List<BaseItemDto>> = try {
		var request = GetItemsRequest(
			searchTerm = searchTerm,
			limit = QUERY_LIMIT,
			imageTypeLimit = 1,
			includeItemTypes = itemTypes,
			fields = ItemRepository.itemFields,
			recursive = true,
			enableTotalRecordCount = false,
		)

		// Special case for video row
		if (itemTypes.size == 1 && itemTypes.first() == BaseItemKind.VIDEO) {
			request = request.copy(
				mediaTypes = setOf(MediaType.VIDEO),
				includeItemTypes = null,
				excludeItemTypes = setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.TV_CHANNEL)
			)
		}

		val serverItems = withContext(Dispatchers.IO) {
			apiClient.itemsApi.getItems(request).content.items
		}

		val kind = itemTypes.singleOrNull()
		val items = if (kind != null && kind in FORGIVING_KINDS) {
			runCatching { forgivingSearch(searchTerm, kind, serverItems) }
				.onFailure { Timber.w(it, "Forgiving search failed, using the server's results") }
				.getOrDefault(serverItems)
		} else serverItems

		Result.success(items)
	} catch (e: ApiClientException) {
		Timber.e(e, "Failed to search for items")
		Result.failure(e)
	}

	/**
	 * The server only matches the title as typed ("spider-man" finds Spider-Man, "spiderman"
	 * finds nothing). Match against a cached list of titles with punctuation, spacing and case
	 * removed instead, then fetch the full items for the best matches.
	 */
	private suspend fun forgivingSearch(
		searchTerm: String,
		kind: BaseItemKind,
		serverItems: List<BaseItemDto>,
	): List<BaseItemDto> {
		val queryCompact = compact(searchTerm)
		val queryWords = words(searchTerm)
		if (queryCompact.isEmpty()) return serverItems

		val ranked = titleIndex(kind).entries.mapNotNull { entry ->
			val score = entry.compactNames.minOf { name -> score(name, queryCompact, queryWords) }
			if (score == Int.MAX_VALUE) null else entry to score
		}.sortedWith(compareBy({ it.second }, { it.first.sortName }))
			.take(QUERY_LIMIT)
			.map { it.first.id }

		val serverIds = serverItems.map { it.id }.toSet()
		val missing = ranked.filterNot { it in serverIds }
		if (missing.isEmpty()) return serverItems

		val fetched = withContext(Dispatchers.IO) {
			apiClient.itemsApi.getItems(
				GetItemsRequest(
					ids = missing,
					imageTypeLimit = 1,
					fields = ItemRepository.itemFields,
					enableTotalRecordCount = false,
				)
			).content.items
		}.associateBy { it.id }

		// Forgiving ranking first; anything the server found that the index didn't stays at the end.
		val serverById = serverItems.associateBy { it.id }
		val rankedSet = ranked.toSet()
		val ordered = ranked.mapNotNull { serverById[it] ?: fetched[it] }
		return (ordered + serverItems.filterNot { it.id in rankedSet }).take(QUERY_LIMIT)
	}

	/** Lower is better; Int.MAX_VALUE = no match. */
	private fun score(name: String, query: String, queryWords: List<String>): Int = when {
		name.isEmpty() -> Int.MAX_VALUE
		name == query -> 0
		name.startsWith(query) -> 1
		name.removePrefix("the").startsWith(query) -> 2
		name.contains(query) -> 3
		queryWords.size > 1 && queryWords.all { name.contains(it) } -> 4
		else -> Int.MAX_VALUE
	}

	private suspend fun titleIndex(kind: BaseItemKind): TitleIndex = indexMutex.withLock {
		indexes[kind]?.takeIf { it.builtAt.elapsedNow() < INDEX_MAX_AGE }?.let { return@withLock it }

		val items = withContext(Dispatchers.IO) {
			apiClient.itemsApi.getItems(
				GetItemsRequest(
					includeItemTypes = setOf(kind),
					recursive = true,
					fields = setOf(ItemFields.SORT_NAME, ItemFields.ORIGINAL_TITLE),
					enableImages = false,
					enableUserData = false,
					enableTotalRecordCount = false,
				)
			).content.items
		}

		TitleIndex(
			entries = items.map { item ->
				val names = listOfNotNull(item.name, item.originalTitle).map(::compact).filter { it.isNotEmpty() }.distinct()
				TitleEntry(item.id, names.ifEmpty { listOf("") }, item.sortName ?: item.name.orEmpty())
			},
			builtAt = TimeSource.Monotonic.markNow(),
		).also { indexes[kind] = it }
	}
}
