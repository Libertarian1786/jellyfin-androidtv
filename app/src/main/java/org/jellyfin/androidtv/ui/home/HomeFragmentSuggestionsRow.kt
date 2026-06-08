package org.jellyfin.androidtv.ui.home

import android.content.Context
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.Row
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * A home row backed by a pre-computed static list of items (the output of the
 * recommender). Mirrors [HomeFragmentBrowseRowDefRow]'s add sequence but uses
 * the static-items [ItemRowAdapter] constructor instead of a server query.
 */
class HomeFragmentSuggestionsRow(
	private val title: String,
	private val items: List<BaseItemDto>,
) : HomeFragmentRow {
	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		if (items.isEmpty()) return
		val rowAdapter = ItemRowAdapter(context, items, cardPresenter, rowsAdapter, true)
		val row = ListRow(HeaderItem(title), rowAdapter)
		rowAdapter.setRow(row)
		rowAdapter.Retrieve()
		rowsAdapter.add(row)
	}
}
