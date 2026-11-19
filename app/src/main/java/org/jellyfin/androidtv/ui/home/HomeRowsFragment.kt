package org.jellyfin.androidtv.ui.home

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.NotificationsRepository
import org.jellyfin.androidtv.data.repository.RecommendationsRepository
import org.jellyfin.androidtv.data.repository.UserViewsRepository
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.browsing.CompositeClickedListener
import org.jellyfin.androidtv.ui.browsing.CompositeSelectedListener
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.refreshItem
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.AudioEventListener
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.presentation.PositionableListRowPresenter
import org.jellyfin.androidtv.util.KeyProcessor
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import android.widget.Toast
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.LocalDate
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Single knob for the height (dp) of all poster cards on the home rows. */
private const val HOME_CARD_HEIGHT = 114

/** Months where the Christmas row leads instead of being pinned to the bottom. */
private val CHRISTMAS_MONTHS = 11..12

class HomeRowsFragment : RowsSupportFragment(), AudioEventListener, View.OnKeyListener {
	private val api by inject<ApiClient>()
	private val backgroundService by inject<BackgroundService>()
	private val playbackManager by inject<PlaybackManager>()
	private val mediaManager by inject<MediaManager>()
	private val notificationsRepository by inject<NotificationsRepository>()
	private val userRepository by inject<UserRepository>()
	private val userSettingPreferences by inject<UserSettingPreferences>()
	private val userViewsRepository by inject<UserViewsRepository>()
	private val recoRepo by lazy { RecommendationsRepository(api) }
	private val dataRefreshService by inject<DataRefreshService>()
	private val customMessageRepository by inject<CustomMessageRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val itemLauncher by inject<ItemLauncher>()
	private val playbackLauncher by inject<PlaybackLauncher>()
	private val keyProcessor by inject<KeyProcessor>()

	private val helper by lazy { HomeFragmentHelper(requireContext(), userRepository) }

	// Data
	private var currentItem: BaseRowItem? = null
	private var currentRow: ListRow? = null
	private var justLoaded = true

	/** The themed collection rows currently on screen, so a refresh can replace them. */
	private val collectionRows = mutableListOf<Row>()

	// Special rows
	private val notificationsRow by lazy { NotificationsHomeFragmentRow(lifecycleScope, notificationsRepository) }
	private val nowPlaying by lazy { HomeFragmentNowPlayingRow(lifecycleScope, playbackManager, mediaManager) }
	private val liveTVRow by lazy { HomeFragmentLiveTVRow(requireActivity(), userRepository, navigationRepository) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val rowTopPadding = (6 * resources.displayMetrics.density).toInt()
		// XSMALL focus zoom is a home-only choice; other screens keep the stock zoom.
		adapter = MutableObjectAdapter<Row>(PositionableListRowPresenter(rowTopPadding, FocusHighlight.ZOOM_FACTOR_XSMALL))

		lifecycleScope.launch(Dispatchers.IO) {
			val currentUser = withTimeout(30.seconds) {
				userRepository.currentUser.filterNotNull().first()
			}

			// Make sure the rows are empty
			val rows = mutableListOf<HomeFragmentRow>()

			// Check for coroutine cancellation
			if (!isActive) return@launch

			// Curated home order. Recently Added (LATEST_MEDIA) is intentionally left out.
			// No Next Up row: David doesn't want it (2026-11-18).
			rows.add(helper.loadResumeVideo())            // Continue Watching
			rows.add(helper.loadMyList())                 // My List (hearted titles)

			// Suggested for You — hydrated from the cached recommender id list. Bounded by a
			// short timeout so a slow server can't hold up the whole home screen; the row is
			// simply skipped this launch if hydration is slow. Cache refreshes further below.
			val suggestionItems = withTimeoutOrNull(3.seconds) {
				recoRepo.fetchItemsByIds(recoRepo.readCachedIds(requireContext()))
			}.orEmpty()
			if (suggestionItems.isNotEmpty()) {
				rows.add(HomeFragmentSuggestionsRow(requireContext().getString(R.string.suggested_for_you), suggestionItems))
			}

			// No Recently watched row: David, 2026-11-19 ("no one needs" it).
			rows.add(HomeFragmentViewsRow(small = true))  // My media (library shortcuts)
			rows.add(helper.loadResumeAudio())            // Continue Listening (hidden when empty)
			if (currentUser.policy?.enableLiveTvAccess == true) {
				rows.add(liveTVRow)
				rows.add(helper.loadOnNow())
			}

			// Add sections to layout
			withContext(Dispatchers.Main) {
				val cardPresenter = CardPresenter(true, HOME_CARD_HEIGHT)

				// Add rows in order
				notificationsRow.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)
				nowPlaying.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)
				for (row in rows) row.addToRowsAdapter(requireContext(), cardPresenter, adapter as MutableObjectAdapter<Row>)

				// Wire up Live TV sibling rows so the On Now row removes the buttons row when empty
				@Suppress("UNCHECKED_CAST")
				val rowsAdapter = adapter as MutableObjectAdapter<Row>
				for (i in 0 until rowsAdapter.size()) {
					val listRow = rowsAdapter.get(i) as? ListRow ?: continue
					val itemAdapter = listRow.adapter as? ItemRowAdapter ?: continue
					if (itemAdapter.queryType == QueryType.LiveTvProgram && i > 0) {
						val previousRow = rowsAdapter.get(i - 1)
						if (previousRow != null) itemAdapter.setSiblingRow(previousRow)
					}
				}
			}

			// Themed collection rows: shown instantly from a cached list, then refreshed
			// from the Collections library in the background.
			addThemedCollectionRows()

			// No Recently Added row: David doesn't want it on any device (2026-09-23).

			// Refresh the recommender cache in the background so the next home load
			// reflects the latest watch history.
			if (isActive) runCatching { recoRepo.computeSuggestions(requireContext()) }
		}

		onItemViewClickedListener = CompositeClickedListener().apply {
			registerListener(ItemViewClickedListener())
			registerListener(liveTVRow::onItemClicked)
			registerListener(notificationsRow::onItemClicked)
		}

		onItemViewSelectedListener = CompositeSelectedListener().apply {
			registerListener(ItemViewSelectedListener())
		}

		customMessageRepository.message
			.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.onEach { message ->
				when (message) {
					CustomMessage.RefreshCurrentItem -> refreshCurrentItem()
					else -> Unit
				}
			}.launchIn(lifecycleScope)

		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				// Watch progress only moves Continue Watching / Next Up, and arrives every few
				// seconds while anything plays on another device - refresh just those rows,
				// at most every 30 s. Library changes come in bursts (a scan, artwork, theme
				// songs), so a burst is coalesced into one full refresh after it goes quiet.
				// Re-fetching all 60-odd rows per message froze the 2 GB Chromecast.
				api.webSocket.subscribe<UserDataChangedMessage>()
					.onEach { progressRefresh.request() }
					.launchIn(this)

				api.webSocket.subscribe<LibraryChangedMessage>()
					.onEach { libraryRefresh.request() }
					.launchIn(this)
			}
		}

		// Subscribe to Audio messages
		mediaManager.addAudioEventListener(this)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		// Pack the rows closer together so more fit on screen below the hero.
		verticalGridView?.setItemSpacing(0)
		// Pin the focused row near the top so the row above doesn't peek into view.
		verticalGridView?.windowAlignment = BaseGridView.WINDOW_ALIGN_LOW_EDGE
		verticalGridView?.windowAlignmentOffsetPercent = 5f
		verticalGridView?.setItemAlignmentOffsetPercent(0f)
		// Keep the rows transparent so the full-screen backdrop shows through behind them.
		view.background = null
		verticalGridView?.background = null
	}

	/**
	 * Adds a home row for each of the user's curated collections. The list is cached
	 * so repeat launches render instantly while a fresh copy loads in the background.
	 */
	private suspend fun addThemedCollectionRows() {
		val prefs = requireContext().getSharedPreferences("jellyfintv_home_cache", Context.MODE_PRIVATE)

		val cached = prefs.getString("themed_collections", null)
			?.lineSequence()
			?.mapNotNull { line ->
				val parts = line.split('\t', limit = 2)
				if (parts.size != 2) return@mapNotNull null
				val id = runCatching { UUID.fromString(parts[0]) }.getOrNull() ?: return@mapNotNull null
				id to parts[1]
			}
			?.toList()
			.orEmpty()

		if (cached.isNotEmpty()) withContext(Dispatchers.Main) { addCollectionRowsToAdapter(cached) }

		// Refresh from the Collections library directly (faster than a recursive scan)
		// and skip the auto-generated "... Collection" franchise box sets.
		val fresh = runCatching {
			val collectionsView = userViewsRepository.views.first()
				.firstOrNull { it.collectionType == CollectionType.BOXSETS }
			val request = if (collectionsView != null) {
				GetItemsRequest(
					parentId = collectionsView.id,
					sortBy = setOf(ItemSortBy.SORT_NAME),
					fields = setOf(ItemFields.PROVIDER_IDS),
					enableTotalRecordCount = false,
				)
			} else {
				GetItemsRequest(
					includeItemTypes = setOf(BaseItemKind.BOX_SET),
					recursive = true,
					sortBy = setOf(ItemSortBy.SORT_NAME),
					fields = setOf(ItemFields.PROVIDER_IDS),
					enableTotalRecordCount = false,
				)
			}
			api.itemsApi.getItems(request).content.items.orEmpty()
		}.getOrDefault(emptyList())
			.mapNotNull { item ->
				val name = item.name ?: return@mapNotNull null
				// Franchise sets (TMDb collections like "The Space Odyssey Series", "DC Universe")
				// carry a TMDb id; the themed bands deliberately don't.
				if (name.endsWith(" Collection") || item.providerIds?.containsKey("Tmdb") == true) null else item.id to name
			}

		if (fresh.isNotEmpty() && fresh != cached) {
			prefs.edit()
				.putString("themed_collections", fresh.joinToString("\n") { "${it.first}\t${it.second}" })
				.apply()
			// Render the fresh list now (replacing the cached rows) rather than leaving it
			// for the next launch — otherwise a newly created collection stays invisible
			// for a whole extra app start.
			withContext(Dispatchers.Main) { addCollectionRowsToAdapter(fresh) }
		}
	}

	private fun addCollectionRowsToAdapter(collections: List<Pair<UUID, String>>) {
		val cardPresenter = CardPresenter(true, HOME_CARD_HEIGHT)
		@Suppress("UNCHECKED_CAST")
		val rowsAdapter = adapter as MutableObjectAdapter<Row>
		// Drop any set rendered from the cache first, so refreshing replaces those rows
		// instead of appending a duplicate copy of every collection. Tracked by reference
		// because other rows (now playing, notifications) can shift the indices.
		if (collectionRows.isNotEmpty()) {
			for (row in collectionRows) rowsAdapter.remove(row)
			collectionRows.clear()
		}
		val firstNewRow = rowsAdapter.size()
		// Rotate the (alphabetical) list by a date-based offset so a different set of
		// collections leads each day, cycling through all of them over time. Stable
		// within a day so navigating doesn't reshuffle the rows under you.
		val rotated = if (collections.size > 1) {
			val offset = (LocalDate.now().toEpochDay() % collections.size).toInt()
			collections.drop(offset) + collections.take(offset)
		} else {
			collections
		}
		// Christmas is seasonal: lead with it in Nov/Dec, and pin it to the very bottom
		// the rest of the year so the daily rotation can't surface it in July. Covers every
		// "Christmas ..." row (Christmas Movies, Christmas TV), always movies first.
		val (christmas, others) = rotated
			.partition { it.second.startsWith("Christmas", ignoreCase = true) }
			.let { (c, o) -> c.sortedBy { it.second.lowercase() } to o }
		val ordered = when {
			christmas.isEmpty() -> rotated
			LocalDate.now().monthValue in CHRISTMAS_MONTHS -> christmas + others
			else -> others + christmas
		}
		for ((id, name) in ordered) {
			helper.loadCollectionRow(name, id).addToRowsAdapter(requireContext(), cardPresenter, rowsAdapter)
		}
		// Remember what we just added so a later refresh can replace exactly these rows.
		for (i in firstNewRow until rowsAdapter.size()) {
			rowsAdapter.get(i)?.let(collectionRows::add)
		}
	}

	/**
	 * Plays the named server playlist in its own order (a different show each time). From the
	 * "Shuffle" card it starts at a random point; from an episode card it starts with that episode.
	 */
	private fun playPlaylist(name: String, startWith: BaseItemDto? = null) {
		lifecycleScope.launch {
			val items = runCatching {
				withContext(Dispatchers.IO) {
					val playlist = api.itemsApi.getItems(
						GetItemsRequest(
							includeItemTypes = setOf(BaseItemKind.PLAYLIST),
							recursive = true,
							searchTerm = name,
						)
					).content.items.firstOrNull { it.name.equals(name, ignoreCase = true) }
						?: return@withContext emptyList()
					api.itemsApi.getItems(
						GetItemsRequest(parentId = playlist.id, fields = ItemRepository.itemFields)
					).content.items
				}
			}.getOrElse {
				Timber.w(it, "Could not load playlist %s", name)
				emptyList()
			}
			if (!isAdded) return@launch
			if (startWith != null) {
				// The chosen episode first, then the playlist from just after it (wrapping round)
				val at = items.indexOfFirst { it.id == startWith.id }
				val queue = if (at >= 0) items.drop(at) + items.take(at) else listOf(startWith) + items
				playbackLauncher.launch(requireContext(), keepPartsTogether(queue))
				return@launch
			}
			if (items.isEmpty()) {
				Toast.makeText(requireContext(), "\"$name\" is empty or missing", Toast.LENGTH_LONG).show()
				return@launch
			}
			// Start somewhere new each press; the playlist's own order (a different show each time) is kept
			val start = items.indices.random()
			playbackLauncher.launch(requireContext(), keepPartsTogether(items.drop(start) + items.take(start)))
		}
	}

	/**
	 * Two-part Christmas episodes play back to back (David 2026-11-19): an episode is followed
	 * straight away by the same show's next episode (same season, next number) when that one is in
	 * the list too. Whole runs move together, placed where their first part falls in the queue.
	 */
	private fun keepPartsTogether(queue: List<BaseItemDto>): List<BaseItemDto> {
		fun key(it: BaseItemDto) = Triple(it.seriesId, it.parentIndexNumber, it.indexNumber)
		val byKey = queue.filter { it.seriesId != null && it.indexNumber != null }.associateBy(::key)
		val placed = HashSet<java.util.UUID>()
		val out = ArrayList<BaseItemDto>(queue.size)
		for (item in queue) {
			if (!placed.add(item.id)) continue
			// a Part 2 whose Part 1 is in the list waits for Part 1 (unless it is the one picked to play first)
			val prev = item.indexNumber?.let { byKey[Triple(item.seriesId, item.parentIndexNumber, it - 1)] }
			if (out.isNotEmpty() && prev != null && prev.id !in placed) {
				placed.remove(item.id)
				continue
			}
			out.add(item)
			var next = item.indexNumber?.let { byKey[Triple(item.seriesId, item.parentIndexNumber, it + 1)] }
			while (next != null && placed.add(next.id)) {
				out.add(next)
				next = next.indexNumber?.let { byKey[Triple(next!!.seriesId, next!!.parentIndexNumber, it + 1)] }
			}
		}
		return out
	}

	/** Back twice on the home screen: the first title of the row in focus. */
	fun scrollToRowStart() {
		if ((adapter?.size() ?: 0) == 0) return
		setSelectedPosition(selectedPosition.coerceAtLeast(0), true, ListRowPresenter.SelectItemViewHolderTask(0))
		verticalGridView?.requestFocus()
	}

	override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
		if (event?.action != KeyEvent.ACTION_UP) return false
		return keyProcessor.handleKey(keyCode, currentItem, activity)
	}

	override fun onResume() {
		super.onResume()

		// The home uses the hero for backdrops; keep the blurred full-screen
		// background off here (also avoids re-blurring an image on every hover).
		backgroundService.clearBackgrounds()

		//React to deletion
		if (currentRow != null && currentItem != null && currentItem?.baseItem != null && currentItem!!.baseItem!!.id == dataRefreshService.lastDeletedItemId) {
			(currentRow!!.adapter as ItemRowAdapter).remove(currentItem)
			currentItem = null
			dataRefreshService.lastDeletedItemId = null
		}

		if (!justLoaded) {
			//Re-retrieve anything that needs it but delay slightly so we don't take away gui landing
			refreshCurrentItem()
			refreshRows()
		} else {
			justLoaded = false
		}

		// Update audio queue
		Timber.i("Updating audio queue in HomeFragment (onResume)")
		nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>)
	}

	override fun onQueueStatusChanged(hasQueue: Boolean) {
		if (activity == null || requireActivity().isFinishing) return

		Timber.i("Updating audio queue in HomeFragment (onQueueStatusChanged)")
		nowPlaying.update(requireContext(), adapter as MutableObjectAdapter<Row>)
	}

	private fun refreshRows(
		force: Boolean = false,
		delayed: Boolean = true,
		only: ((QueryType) -> Boolean)? = null,
	) {
		lifecycleScope.launch(Dispatchers.IO) {
			if (delayed) delay(1.5.seconds)

			repeat(adapter.size()) { i ->
				val rowAdapter = (adapter[i] as? ListRow)?.adapter as? ItemRowAdapter
				// Static rows (Suggested for You) must not re-Retrieve: loadStaticItems
				// re-adds all items without clearing (duplicates the row) and would
				// notify the RecyclerView from this IO thread.
				if (rowAdapter?.queryType == QueryType.StaticItems) return@repeat
				if (only != null && (rowAdapter == null || !only(rowAdapter.queryType))) return@repeat
				if (force) rowAdapter?.Retrieve()
				else rowAdapter?.ReRetrieveIfNeeded()
			}
		}
	}

	private val progressRefresh = ThrottledRefresh(30.seconds) {
		refreshRows(force = true, delayed = false) { it == QueryType.Resume || it == QueryType.NextUp }
	}

	// A library scan sends messages for minutes; wait for a quiet minute (capped at five) so
	// the shuffled collection rows are not reordered under the viewer every minute.
	private val libraryRefresh = DebouncedRefresh(quiet = 60.seconds, maxWait = 5.minutes) {
		refreshRows(force = true, delayed = false)
	}

	/**
	 * Runs [action] at most once per [minGap]. A request inside the gap is not dropped: one
	 * trailing run is scheduled for the end of the gap, and further requests fold into it.
	 * Runs only while home is on screen (the fragment is merely detached during playback).
	 */
	private inner class ThrottledRefresh(
		private val minGap: Duration,
		private val action: () -> Unit,
	) {
		private var lastRun = TimeSource.Monotonic.markNow() - minGap
		private var pending: Job? = null

		fun request() {
			if (pending?.isActive == true) return
			val wait = minGap - lastRun.elapsedNow()
			pending = lifecycleScope.launch {
				if (wait.isPositive()) delay(wait)
				lifecycle.withResumed {
					lastRun = TimeSource.Monotonic.markNow()
					action()
				}
			}
		}
	}

	/**
	 * Runs [action] once requests have stopped for [quiet], but no later than [maxWait] after
	 * the first request of a burst. Runs only while home is on screen.
	 */
	private inner class DebouncedRefresh(
		private val quiet: Duration,
		private val maxWait: Duration,
		private val action: () -> Unit,
	) {
		private var burstStart: TimeSource.Monotonic.ValueTimeMark? = null
		private var pending: Job? = null

		fun request() {
			val start = burstStart ?: TimeSource.Monotonic.markNow().also { burstStart = it }
			pending?.cancel()
			val wait = minOf(quiet, maxWait - start.elapsedNow())
			pending = lifecycleScope.launch {
				if (wait.isPositive()) delay(wait)
				lifecycle.withResumed {
					burstStart = null
					action()
				}
			}
		}
	}

	private fun refreshCurrentItem() {
		val adapter = currentRow?.adapter as? ItemRowAdapter ?: return
		val item = currentItem ?: return

		Timber.i("Refresh item ${item.getFullName(requireContext())}")
		adapter.refreshItem(api, this, item)
	}

	override fun onDestroy() {
		super.onDestroy()

		mediaManager.removeAudioEventListener(this)
	}

	private inner class ItemViewClickedListener : OnItemViewClickedListener {
		override fun onItemClicked(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			if (item !is BaseRowItem) return
			if (row !is ListRow) return
			val shuffle = (item as? GridButtonBaseRowItem)?.gridButton as? ShufflePlaylistButton
			if (shuffle != null) {
				playPlaylist(shuffle.playlistName)
				return
			}
			// Christmas TV only (David 2026-11-19): an episode plays right away and is followed by
			// another show's Christmas episode (the playlist order), not that show's next episode.
			val bandPlaylist = ShufflePlaylistButton.PLAYLIST_FOR_BAND.entries
				.firstOrNull { it.key.equals(row.headerItem?.name, ignoreCase = true) }?.value
			val episode = item.baseItem?.takeIf { it.type == BaseItemKind.EPISODE }
			if (bandPlaylist != null && episode != null) {
				playPlaylist(bandPlaylist, startWith = episode)
				return
			}
			val seeAll = (item as? GridButtonBaseRowItem)?.gridButton as? SeeAllButton
			if (seeAll != null) {
				navigationRepository.navigate(
					Destinations.libraryBrowser(
						BaseItemDto(
							id = seeAll.collectionId,
							type = BaseItemKind.BOX_SET,
							name = seeAll.collectionName,
							isFolder = true,
							// the grid keys its sort/size settings on this; a collection uses its own id
							displayPreferencesId = seeAll.collectionId.toString().replace("-", ""),
						)
					)
				)
				return
			}
			@Suppress("UNCHECKED_CAST")
			itemLauncher.launch(item, row.adapter as MutableObjectAdapter<Any>, requireContext())
		}
	}

	private inner class ItemViewSelectedListener : OnItemViewSelectedListener {
		override fun onItemSelected(
			itemViewHolder: Presenter.ViewHolder?,
			item: Any?,
			rowViewHolder: RowPresenter.ViewHolder?,
			row: Row?,
		) {
			if (item !is BaseRowItem) {
				currentItem = null
				//fill in default background
				backgroundService.clearBackgrounds()
			} else {
				currentItem = item
				currentRow = row as ListRow

				val itemRowAdapter = row.adapter as? ItemRowAdapter
				itemRowAdapter?.loadMoreItemsIfNeeded(itemRowAdapter.indexOf(item))

				backgroundService.setCurrentItem(item.baseItem)
			}
		}
	}
}
