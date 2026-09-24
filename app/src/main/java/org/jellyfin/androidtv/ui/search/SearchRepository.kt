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
import kotlin.math.abs
import kotlin.math.min
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

		/** Kinds searched forgivingly: punctuation, spacing, word order, typos, initials... */
		private val FORGIVING_KINDS = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
		private val INDEX_MAX_AGE = 1.hours

		private val nonAlphanumeric = Regex("[^a-z0-9]+")
		private val diacritics = Regex("\\p{Mn}+")

		/** "two" and "ii" both become "2", so "rocky two" finds Rocky II. */
		private val numberWords = mapOf(
			"one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6",
			"seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12",
			"ii" to "2", "iii" to "3", "iv" to "4", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9",
		)
		private val initialStopWords = setOf("the", "of", "and", "a", "an")
		private val leadingArticles = setOf("the", "a", "an")
		private val titleParts = Regex("\\s*(?::|\\s-\\s)\\s*")

		/**
		 * Abbreviations people type for a title: the first letters of the whole title and of each
		 * part after a colon, each with and without a leading article. "The Lord of the Rings: ..."
		 * gives "lotr..."; "Star Trek: The Next Generation" gives "st..." and "tng".
		 */
		fun abbreviations(raw: String): Set<String> = buildSet {
			for (part in listOf(raw) + raw.split(titleParts).drop(1)) {
				val partWords = words(part)
				if (partWords.isEmpty()) continue
				add(partWords.joinToString("") { it.take(1) })
				if (partWords.size > 1 && partWords.first() in leadingArticles) {
					add(partWords.drop(1).joinToString("") { it.take(1) })
				}
			}
		}

		fun words(text: String): List<String> = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
			.replace(diacritics, "")
			.replace("&", " and ")
			.split(nonAlphanumeric)
			.filter { it.isNotEmpty() }
			.map { numberWords[it] ?: it }

		/** "Joe's College: Road-Trip" -> "joescollegeroadtrip". */
		fun compact(text: String): String = words(text).joinToString("")

		/** One typo allowed from 5 letters, two from 8. */
		private fun typoBudget(word: String) = when {
			word.length >= 8 -> 2
			word.length >= 5 -> 1
			else -> 0
		}

		private fun editDistance(a: String, b: String, limit: Int): Int {
			if (abs(a.length - b.length) > limit) return limit + 1
			var previous = IntArray(b.length + 1) { it }
			for (i in 1..a.length) {
				val current = IntArray(b.length + 1)
				current[0] = i
				var rowMin = current[0]
				for (j in 1..b.length) {
					val cost = if (a[i - 1] == b[j - 1]) 0 else 1
					current[j] = min(min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost)
					rowMin = min(rowMin, current[j])
				}
				if (rowMin > limit) return limit + 1
				previous = current
			}
			return previous[b.length]
		}
	}

	private class TitleName(val compact: String, val words: List<String>, val abbreviations: Set<String>, val keyInitials: String)

	private class TitleEntry(val id: UUID, val names: List<TitleName>, val sortName: String)

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
	 * finds nothing). Match against a cached list of titles instead, forgiving punctuation,
	 * spacing, word order, number words, small typos and initials; then add the films of a
	 * matching collection ("star wars") or person ("tom hanks"). Full items are fetched by id.
	 */
	private suspend fun forgivingSearch(
		searchTerm: String,
		kind: BaseItemKind,
		serverItems: List<BaseItemDto>,
	): List<BaseItemDto> {
		val queryCompact = compact(searchTerm)
		val queryWords = words(searchTerm)
		if (queryCompact.isEmpty()) return serverItems

		val scores = mutableMapOf<UUID, Pair<Int, String>>()
		fun offer(id: UUID, score: Int, sortName: String) {
			val existing = scores[id]
			if (existing == null || score < existing.first) scores[id] = score to sortName
		}

		for (entry in titleIndex(kind).entries) {
			val score = entry.names.minOf { score(it, queryCompact, queryWords) }
			if (score != Int.MAX_VALUE) offer(entry.id, score, entry.sortName)
		}

		// Collections: "star wars" -> every film in a Star Wars collection. Themed rows count too
		// ("westerns" -> the Western row), after the direct title matches.
		if (queryCompact.length >= 3) {
			val collections = titleIndex(BaseItemKind.BOX_SET).entries
				.map { it to it.names.minOf { name -> score(name, queryCompact, queryWords) } }
				.filter { it.second <= 3 }
				.sortedBy { it.second }
				.take(2)
			for ((collection, _) in collections) {
				collectionMembers(collection.id, kind).forEach { (id, sortName) -> offer(id, 7, sortName) }
			}
		}

		// People: "tom hanks" -> his films, not just a person card.
		if (queryWords.size >= 2) {
			val person = withContext(Dispatchers.IO) {
				apiClient.itemsApi.getItems(
					GetItemsRequest(
						searchTerm = searchTerm,
						includeItemTypes = setOf(BaseItemKind.PERSON),
						limit = 1,
						enableImages = false,
						enableTotalRecordCount = false,
					)
				).content.items.firstOrNull()
			}
			if (person != null && compact(person.name.orEmpty()) == queryCompact) {
				personTitles(person.id, kind).forEach { (id, sortName) -> offer(id, 8, sortName) }
			}
		}

		val ranked = scores.entries
			.sortedWith(compareBy({ it.value.first }, { it.value.second }))
			.take(QUERY_LIMIT)
			.map { it.key }

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
	private fun score(name: TitleName, query: String, queryWords: List<String>): Int {
		val title = name.compact
		return when {
			title.isEmpty() -> Int.MAX_VALUE
			title == query -> 0
			title.startsWith(query) -> 1
			title.removePrefix("the").startsWith(query) -> 2
			// substrings only from 3 letters: "hp" inside "blair witch project" is noise
			query.length >= 3 && title.contains(query) -> 3
			// "star trek tng": every word is in the title or is one of its abbreviations
			queryWords.size > 1 && queryWords.all { title.contains(it) || (it.length >= 2 && it in name.abbreviations) } -> 4
			// "lotr", "tng", "hp": the first letters of the title's words or of a part of it
			queryWords.size == 1 && query.length in 2..6 &&
				(name.keyInitials.startsWith(query) || name.abbreviations.any { it.startsWith(query) }) -> 5
			// small typos: every query word is close to some title word
			queryWords.any { typoBudget(it) > 0 } && queryWords.all { word ->
				val budget = typoBudget(word)
				name.words.any { titleWord ->
					titleWord.startsWith(word) || (budget > 0 && editDistance(word, titleWord, budget) <= budget)
				}
			} -> 6
			else -> Int.MAX_VALUE
		}
	}

	private suspend fun collectionMembers(collectionId: UUID, kind: BaseItemKind): List<Pair<UUID, String>> =
		withContext(Dispatchers.IO) {
			apiClient.itemsApi.getItems(
				GetItemsRequest(
					parentId = collectionId,
					includeItemTypes = setOf(kind),
					fields = setOf(ItemFields.SORT_NAME),
					enableImages = false,
					enableUserData = false,
					enableTotalRecordCount = false,
				)
			).content.items.map { it.id to (it.sortName ?: it.name.orEmpty()) }
		}

	private suspend fun personTitles(personId: UUID, kind: BaseItemKind): List<Pair<UUID, String>> =
		withContext(Dispatchers.IO) {
			apiClient.itemsApi.getItems(
				GetItemsRequest(
					personIds = setOf(personId),
					includeItemTypes = setOf(kind),
					recursive = true,
					fields = setOf(ItemFields.SORT_NAME),
					enableImages = false,
					enableUserData = false,
					enableTotalRecordCount = false,
				)
			).content.items.map { it.id to (it.sortName ?: it.name.orEmpty()) }
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
				val names = listOfNotNull(item.name, item.originalTitle).distinct().mapNotNull { raw ->
					val titleWords = words(raw)
					if (titleWords.isEmpty()) null
					else TitleName(
						compact = titleWords.joinToString(""),
						words = titleWords,
						abbreviations = abbreviations(raw),
						keyInitials = titleWords.filterNot { it in initialStopWords }.joinToString("") { it.take(1) },
					)
				}
				TitleEntry(item.id, names.ifEmpty { listOf(TitleName("", emptyList(), emptySet(), "")) }, item.sortName ?: item.name.orEmpty())
			},
			builtAt = TimeSource.Monotonic.markNow(),
		).also { indexes[kind] = it }
	}
}
