package org.jellyfin.androidtv.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.UserItemDataDto
import timber.log.Timber
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** One recommended item plus the watched title that best explains it. */
data class SuggestionEntry(val id: UUID, val bestSeedName: String?)

/**
 * Content-based "Suggested for You" recommender, computed on-device from the
 * user's own watch history. Builds a weighted taste profile (genre / studio /
 * tag / decade) from what's been watched — recency, completion, play-count and
 * favorites all weighted, IDF-damped so ubiquitous traits don't dominate —
 * then scores unwatched candidates by profile match blended with similarity to
 * specific watched titles, excludes anything already watched / resuming / in
 * Next Up, and diversifies the result (MMR) so it isn't ten near-identical
 * films. Results are cached as an ordered id list for instant render.
 */
class RecommendationsRepository(private val api: ApiClient) {

	private companion object {
		const val PREFS = "jellyfintv_home_cache"
		const val KEY = "suggestions"

		const val PROFILE_CAP = 150
		const val FAV_CAP = 50
		const val CAND_CAP = 200
		const val TOP_GENRES = 4
		const val SEED_TOPN = 25
		const val PROFILE_TOPK = 40
		const val ROW_SIZE = 30
		const val COLD_START_MIN = 5
		const val POPULAR_LIMIT = 100

		const val HALF_LIFE_DAYS = 150.0
		const val RECENCY_FLOOR = 0.10
		const val COMPLETE_PCT = 90.0
		const val PARTIAL_PCT = 40.0
		const val FAVORITE_BOOST = 1.6
		const val PLAYCOUNT_COEF = 0.15
		const val PLAYCOUNT_CAP = 1.6
		const val ALPHA = 0.65
		const val SCORE_FLOOR = 0.02

		const val W_G = 1.0
		const val W_S = 0.6
		const val W_T = 0.5
		const val W_D = 0.3
		const val SIM_MAX = W_G + W_S + W_T + W_D

		val MEDIA = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
		val FIELDS = ItemRepository.browseFields + setOf(ItemFields.STUDIOS, ItemFields.TAGS)
	}

	// ---- Feature model (genre / studio / tag / decade) -------------------------------

	private class Feat(val g: Set<String>, val s: Set<String>, val t: Set<String>, val d: Set<String>) {
		val keys: List<String> = (g + s + t + d).toList()
		val size: Int get() = g.size + s.size + t.size + d.size
	}

	private fun lc(values: List<String>?, prefix: String): Set<String> =
		values.orEmpty().mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty)?.let { v -> "$prefix$v" } }.toSet()

	private fun extract(item: BaseItemDto): Feat = Feat(
		g = lc(item.genres, "g:"),
		s = lc(item.studios?.mapNotNull { it.name }, "s:"),
		t = lc(item.tags, "t:"),
		d = item.productionYear?.let { setOf("d:${(it / 10) * 10}") }.orEmpty(),
	)

	private fun dimW(key: String): Double = when (key.firstOrNull()) {
		'g' -> W_G; 's' -> W_S; 't' -> W_T; else -> W_D
	}

	// ---- Watch-signal weighting ------------------------------------------------------

	private fun seedWeight(ud: UserItemDataDto?): Double {
		if (ud == null || ud.likes == false) return 0.0
		val pct = ud.playedPercentage ?: if (ud.played) 100.0 else 0.0
		val completion = when {
			pct >= COMPLETE_PCT -> 1.0
			pct >= PARTIAL_PCT -> 0.6
			pct > 0 -> 0.25
			ud.played -> 0.8
			ud.playCount >= 1 -> 0.8
			else -> 0.0
		}
		val isFav = ud.isFavorite || ud.likes == true
		if (completion <= 0.0 && !isFav) return 0.0
		val base = if (completion > 0.0) completion else 0.4
		val ageDays = ud.lastPlayedDate
			?.let { maxOf(0L, ChronoUnit.DAYS.between(it, LocalDateTime.now())) }?.toDouble()
			?: HALF_LIFE_DAYS
		val recency = 0.5.pow(ageDays / HALF_LIFE_DAYS).coerceIn(RECENCY_FLOOR, 1.0)
		val fav = if (isFav) FAVORITE_BOOST else 1.0
		val pc = min(1.0 + PLAYCOUNT_COEF * (ud.playCount - 1), PLAYCOUNT_CAP).coerceAtLeast(1.0)
		return base * recency * fav * pc
	}

	private fun excluded(c: BaseItemDto, nextUp: Set<UUID>): Boolean {
		val ud = c.userData
		return ud?.played == true ||
			(ud?.playedPercentage ?: 0.0) >= COMPLETE_PCT ||
			(ud?.playbackPositionTicks ?: 0L) > 0L ||
			ud?.likes == false ||
			c.id in nextUp
	}

	// ---- Public API ------------------------------------------------------------------

	/** Ranked item ids from the last computation (instant, prefs-only). */
	fun readCachedIds(context: Context): List<UUID> =
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
			?.lineSequence()
			?.mapNotNull { line -> runCatching { UUID.fromString(line.substringBefore('\t')) }.getOrNull() }
			?.toList()
			.orEmpty()

	/** Re-hydrate [BaseItemDto]s for cached ids, preserving the ranked order. */
	suspend fun fetchItemsByIds(ids: List<UUID>): List<BaseItemDto> = withContext(Dispatchers.IO) {
		if (ids.isEmpty()) return@withContext emptyList()
		val byId = runCatching {
			api.itemsApi.getItems(
				ids = ids,
				fields = ItemRepository.browseFields,
				imageTypeLimit = 1,
				enableTotalRecordCount = false,
			).content.items.orEmpty()
		}.getOrDefault(emptyList()).associateBy { it.id }
		ids.mapNotNull { byId[it] }
	}

	/** Heavy path: fetch, score, diversify, cache. Caller runs this off the main thread. */
	suspend fun computeSuggestions(context: Context): List<SuggestionEntry> {
		val seeds = fetchSeeds()
		val nextUp = fetchNextUpIds()
		val effective = seeds.count { (it.userData?.playedPercentage ?: if (it.userData?.played == true) 100.0 else 0.0) >= COMPLETE_PCT }
		val coldStart = effective < COLD_START_MIN

		val seedFeat = seeds.associate { it.id to extract(it) }
		val topGenres = run {
			val weight = HashMap<String, Double>()
			for (seed in seeds) {
				val w = seedWeight(seed.userData)
				if (w <= 0.0) continue
				seed.genres?.forEach { weight[it] = (weight[it] ?: 0.0) + w }
			}
			weight.entries.sortedByDescending { it.value }.take(TOP_GENRES).map { it.key }
		}

		val candidates = if (!coldStart && topGenres.isNotEmpty()) fetchCandidates(topGenres) else fetchPopular()

		val ranked = withContext(Dispatchers.Default) {
			rank(seeds, seedFeat, candidates, nextUp, coldStart)
		}
		writeCache(context, ranked)
		Timber.i("Suggestions: seeds=%d effective=%d cold=%b topGenres=%s candidates=%d -> %d", seeds.size, effective, coldStart, topGenres, candidates.size, ranked.size)
		return ranked
	}

	// ---- Fetch -----------------------------------------------------------------------

	private suspend fun fetchSeeds(): List<BaseItemDto> = withContext(Dispatchers.IO) {
		suspend fun query(filter: ItemFilter, limit: Int) = runCatching {
			api.itemsApi.getItems(
				includeItemTypes = MEDIA,
				recursive = true,
				filters = setOf(filter),
				sortBy = setOf(ItemSortBy.DATE_PLAYED),
				sortOrder = setOf(SortOrder.DESCENDING),
				fields = FIELDS,
				imageTypeLimit = 1,
				enableTotalRecordCount = false,
				limit = limit,
			).content.items.orEmpty()
		}.getOrDefault(emptyList())

		(query(ItemFilter.IS_PLAYED, PROFILE_CAP) + query(ItemFilter.IS_FAVORITE_OR_LIKES, FAV_CAP)).distinctBy { it.id }
	}

	private suspend fun fetchCandidates(topGenres: List<String>): List<BaseItemDto> = withContext(Dispatchers.IO) {
		topGenres.flatMap { genre ->
			runCatching {
				api.itemsApi.getItems(
					includeItemTypes = MEDIA,
					recursive = true,
					genres = setOf(genre),
					isPlayed = false,
					sortBy = setOf(ItemSortBy.RANDOM),
					fields = FIELDS,
					imageTypeLimit = 1,
					enableTotalRecordCount = false,
					limit = CAND_CAP,
				).content.items.orEmpty()
			}.getOrDefault(emptyList())
		}.distinctBy { it.id }
	}

	private suspend fun fetchPopular(): List<BaseItemDto> = withContext(Dispatchers.IO) {
		runCatching {
			api.itemsApi.getItems(
				includeItemTypes = MEDIA,
				recursive = true,
				isPlayed = false,
				sortBy = setOf(ItemSortBy.COMMUNITY_RATING),
				sortOrder = setOf(SortOrder.DESCENDING),
				fields = FIELDS,
				imageTypeLimit = 1,
				enableTotalRecordCount = false,
				limit = POPULAR_LIMIT,
			).content.items.orEmpty()
		}.getOrDefault(emptyList())
	}

	private suspend fun fetchNextUpIds(): Set<UUID> = withContext(Dispatchers.IO) {
		runCatching {
			api.tvShowsApi.getNextUp(limit = 50, enableTotalRecordCount = false).content.items.orEmpty().map { it.id }.toSet()
		}.getOrDefault(emptySet())
	}

	// ---- Scoring ---------------------------------------------------------------------

	private class Scored(
		val item: BaseItemDto,
		val feat: Feat,
		var p: Double,
		var sm: Double,
		var score: Double,
		val bestSeedName: String?,
	)

	private fun rank(
		seeds: List<BaseItemDto>,
		seedFeat: Map<UUID, Feat>,
		candidates: List<BaseItemDto>,
		nextUp: Set<UUID>,
		coldStart: Boolean,
	): List<SuggestionEntry> {
		val candFeat = candidates.associate { it.id to extract(it) }

		// IDF over the in-memory pool (seeds ∪ candidates), scikit smoothing.
		val df = HashMap<String, Int>()
		val pool = seeds + candidates
		for (item in pool) {
			val f = if (item.id in candFeat) candFeat.getValue(item.id) else seedFeat[item.id] ?: extract(item)
			for (k in f.keys.distinct()) df[k] = (df[k] ?: 0) + 1
		}
		val n = pool.size
		val idf = { k: String -> ln((1.0 + n) / (1.0 + (df[k] ?: 0))) + 1.0 }

		// Taste profile: raw accumulate, L2-normalize within each dimension, keep top-K.
		val raw = HashMap<String, Double>()
		for (seed in seeds) {
			val w = seedWeight(seed.userData)
			if (w <= 0.0) continue
			for (k in seedFeat.getValue(seed.id).keys) raw[k] = (raw[k] ?: 0.0) + w * idf(k)
		}
		val profile = HashMap<String, Double>()
		raw.entries.groupBy { it.key.first() }.forEach { (_, entries) ->
			val norm = sqrt(entries.sumOf { it.value * it.value })
			if (norm <= 0.0) return@forEach
			entries.sortedByDescending { it.value }.take(PROFILE_TOPK).forEach { profile[it.key] = it.value / norm }
		}

		val topSeeds = seeds.asSequence()
			.map { Triple(it, seedWeight(it.userData), seedFeat.getValue(it.id)) }
			.filter { it.second > 0.0 }
			.sortedByDescending { it.second }
			.take(SEED_TOPN)
			.toList()
		val maxSeedW = topSeeds.maxOfOrNull { it.second } ?: 1.0

		fun simByDim(cw: Double, a: Set<String>, b: Set<String>): Double {
			if (a.isEmpty() || b.isEmpty()) return 0.0
			val inter = a.count { it in b }
			return if (inter == 0) 0.0 else cw * (inter / sqrt(a.size.toDouble() * b.size.toDouble()))
		}
		fun sim(a: Feat, b: Feat): Double =
			simByDim(W_G, a.g, b.g) + simByDim(W_S, a.s, b.s) + simByDim(W_T, a.t, b.t) + simByDim(W_D, a.d, b.d)

		fun profileRaw(f: Feat): Double {
			var total = 0.0
			for (set in listOf(f.g, f.s, f.t, f.d)) {
				if (set.isEmpty()) continue
				var s = 0.0
				for (k in set) s += (profile[k] ?: 0.0) * idf(k)
				total += dimW(set.first()) * (s / sqrt(set.size.toDouble()))
			}
			return total
		}

		// Score every surviving candidate.
		val scored = ArrayList<Scored>()
		for (c in candidates) {
			if (excluded(c, nextUp)) continue
			val f = candFeat.getValue(c.id)
			if (f.size == 0) continue
			val p = profileRaw(f)
			var bestSim = 0.0
			var bestName: String? = null
			for ((seedItem, sw, sf) in topSeeds) {
				val v = (sw / maxSeedW) * sim(f, sf)
				if (v > bestSim) { bestSim = v; bestName = seedItem.name }
			}
			scored += Scored(c, f, p, bestSim, 0.0, bestName)
		}
		if (scored.isEmpty()) return emptyList()

		// Min-max normalize the two components, blend, apply tightly-clamped boosts.
		fun normalize(values: List<Double>): (Double) -> Double {
			val lo = values.min(); val hi = values.max()
			return if (hi - lo <= 1e-9) { _ -> 0.5 } else { v -> (v - lo) / (hi - lo) }
		}
		val normP = normalize(scored.map { it.p })
		val normSm = normalize(scored.map { it.sm })
		val now = LocalDateTime.now()
		for (sc in scored) {
			val p = normP(sc.p); val smv = normSm(sc.sm)
			var s = ALPHA * p + (1 - ALPHA) * smv
			val ageDays = sc.item.dateCreated?.let { ChronoUnit.DAYS.between(it, now) } ?: Long.MAX_VALUE
			s *= when { ageDays < 30 -> 1.20; ageDays < 90 -> 1.08; else -> 1.0 }
			sc.item.communityRating?.let { cr -> s *= (1.0 + 0.10 * ((cr - 6.5) / 3.5)).coerceIn(0.90, 1.10) }
			sc.p = p; sc.sm = smv; sc.score = s
		}

		val pool2 = scored.filter { it.score > SCORE_FLOOR }.toMutableList()
		if (pool2.isEmpty()) return emptyList()

		// MMR diversity re-rank.
		val lambda = if (coldStart) 0.45 else 0.35
		val picked = ArrayList<Scored>()
		picked += pool2.maxBy { it.score }.also { pool2.remove(it) }
		while (picked.size < ROW_SIZE && pool2.isNotEmpty()) {
			val next = pool2.maxBy { c ->
				val redundancy = picked.maxOf { p -> sim(c.feat, p.feat) } / SIM_MAX
				c.score - lambda * redundancy
			}
			picked += next
			pool2.remove(next)
		}
		return picked.map { SuggestionEntry(it.item.id, it.bestSeedName) }
	}

	// ---- Cache -----------------------------------------------------------------------

	private fun writeCache(context: Context, entries: List<SuggestionEntry>) {
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
			.putString(KEY, entries.joinToString("\n") { "${it.id}\t${it.bestSeedName.orEmpty()}" })
			.apply()
	}
}
