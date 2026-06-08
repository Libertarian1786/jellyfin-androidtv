package org.jellyfin.androidtv.ui.home

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.CustomMessage
import org.jellyfin.androidtv.constant.HomeSectionType
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.CustomMessageRepository
import org.jellyfin.androidtv.data.repository.NotificationsRepository
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
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class HomeRowsFragment : RowsSupportFragment(), AudioEventListener, View.OnKeyListener {
	private val api by inject<ApiClient>()
	private val backgroundService by inject<BackgroundService>()
	private val playbackManager by inject<PlaybackManager>()
	private val mediaManager by inject<MediaManager>()
	private val notificationsRepository by inject<NotificationsRepository>()
	private val userRepository by inject<UserRepository>()
	private val userSettingPreferences by inject<UserSettingPreferences>()
	private val userViewsRepository by inject<UserViewsRepository>()
	private val dataRefreshService by inject<DataRefreshService>()
	private val customMessageRepository by inject<CustomMessageRepository>()
	private val navigationRepository by inject<NavigationRepository>()
	private val itemLauncher by inject<ItemLauncher>()
	private val keyProcessor by inject<KeyProcessor>()

	private val helper by lazy { HomeFragmentHelper(requireContext(), userRepository) }

	// Data
	private var currentItem: BaseRowItem? = null
	private var currentRow: ListRow? = null
	private var justLoaded = true

	// Special rows
	private val notificationsRow by lazy { NotificationsHomeFragmentRow(lifecycleScope, notificationsRepository) }
	private val nowPlaying by lazy { HomeFragmentNowPlayingRow(lifecycleScope, playbackManager, mediaManager) }
	private val liveTVRow by lazy { HomeFragmentLiveTVRow(requireActivity(), userRepository, navigationRepository) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val rowTopPadding = (6 * resources.displayMetrics.density).toInt()
		adapter = MutableObjectAdapter<Row>(PositionableListRowPresenter(rowTopPadding))

		lifecycleScope.launch(Dispatchers.IO) {
			val currentUser = withTimeout(30.seconds) {
				userRepository.currentUser.filterNotNull().first()
			}

			// Make sure the rows are empty
			val rows = mutableListOf<HomeFragmentRow>()

			// Check for coroutine cancellation
			if (!isActive) return@launch

			// Curated home order. Recently Added (LATEST_MEDIA) is intentionally NOT added
			// here — it is appended at the very bottom, below the themed collection rows.
			rows.add(helper.loadResumeVideo())            // Continue Watching
			rows.add(helper.loadNextUp())                 // Next Up
			rows.add(HomeFragmentViewsRow(small = true))  // My media (library shortcuts)
			rows.add(helper.loadResumeAudio())            // Continue Listening (hidden when empty)
			if (currentUser.policy?.enableLiveTvAccess == true) {
				rows.add(liveTVRow)
				rows.add(helper.loadOnNow())
			}

			// Add sections to layout
			withContext(Dispatchers.Main) {
				val cardPresenter = CardPresenter(true, 114)

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

			// Recently Added ("Just added" for Movies + Shows) goes at the very bottom,
			// below the themed rows.
			if (isActive) {
				val recentlyAdded = helper.loadRecentlyAdded(userViewsRepository.views.first())
				withContext(Dispatchers.Main) {
					recentlyAdded.addToRowsAdapter(
						requireContext(),
						CardPresenter(true, 114),
						adapter as MutableObjectAdapter<Row>,
					)
				}
			}
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
				api.webSocket.subscribe<UserDataChangedMessage>()
					.onEach { refreshRows(force = true, delayed = false) }
					.launchIn(this)

				api.webSocket.subscribe<LibraryChangedMessage>()
					.onEach { refreshRows(force = true, delayed = false) }
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
					enableTotalRecordCount = false,
				)
			} else {
				GetItemsRequest(
					includeItemTypes = setOf(BaseItemKind.BOX_SET),
					recursive = true,
					sortBy = setOf(ItemSortBy.SORT_NAME),
					enableTotalRecordCount = false,
				)
			}
			api.itemsApi.getItems(request).content.items.orEmpty()
		}.getOrDefault(emptyList())
			.mapNotNull { item ->
				val name = item.name ?: return@mapNotNull null
				if (name.endsWith(" Collection")) null else item.id to name
			}

		if (fresh.isNotEmpty()) {
			prefs.edit()
				.putString("themed_collections", fresh.joinToString("\n") { "${it.first}\t${it.second}" })
				.apply()
			if (cached.isEmpty()) withContext(Dispatchers.Main) { addCollectionRowsToAdapter(fresh) }
		}
	}

	private fun addCollectionRowsToAdapter(collections: List<Pair<UUID, String>>) {
		val cardPresenter = CardPresenter(true, 114)
		@Suppress("UNCHECKED_CAST")
		val rowsAdapter = adapter as MutableObjectAdapter<Row>
		for ((id, name) in collections) {
			helper.loadCollectionRow(name, id).addToRowsAdapter(requireContext(), cardPresenter, rowsAdapter)
		}
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

	private fun refreshRows(force: Boolean = false, delayed: Boolean = true) {
		lifecycleScope.launch(Dispatchers.IO) {
			if (delayed) delay(1.5.seconds)

			repeat(adapter.size()) { i ->
				val rowAdapter = (adapter[i] as? ListRow)?.adapter as? ItemRowAdapter
				if (force) rowAdapter?.Retrieve()
				else rowAdapter?.ReRetrieveIfNeeded()
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
