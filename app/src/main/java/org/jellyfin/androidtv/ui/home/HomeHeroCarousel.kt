package org.jellyfin.androidtv.ui.home

import android.widget.ImageView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.koin.compose.koinInject

/**
 * A hero banner at the top of the home that mirrors the currently focused item:
 * as you move across the rows it shows that item's backdrop with its title logo.
 * It defaults to the most recently added movie until something is hovered, and
 * keeps the last item that actually had a backdrop (so hovering a backdrop-less
 * tile, e.g. a library shortcut, doesn't blank the hero).
 */
@Composable
fun HomeHeroCarousel(modifier: Modifier = Modifier) {
	val api = koinInject<ApiClient>()
	val backgroundService = koinInject<BackgroundService>()
	val focused by backgroundService.currentItem.collectAsState()

	var displayed by remember { mutableStateOf<BaseItemDto?>(null) }

	// Default to the most recently added movie until the user hovers something.
	LaunchedEffect(Unit) {
		if (displayed == null) {
			displayed = runCatching {
				api.itemsApi.getItems(
					includeItemTypes = setOf(BaseItemKind.MOVIE),
					recursive = true,
					sortBy = setOf(ItemSortBy.DATE_CREATED),
					sortOrder = setOf(SortOrder.DESCENDING),
					imageTypes = setOf(ImageType.BACKDROP),
					enableImageTypes = setOf(ImageType.BACKDROP, ImageType.LOGO),
					limit = 1,
				).content.items?.firstOrNull()
			}.getOrNull()
		}
	}

	// Follow focus, but only switch to items that actually have a backdrop.
	LaunchedEffect(focused) {
		val item = focused ?: return@LaunchedEffect
		if (item.itemBackdropImages.isNotEmpty() || item.parentBackdropImages.isNotEmpty()) {
			// Debounce: only swap once focus settles, so fast scrolling doesn't thrash.
			delay(150)
			displayed = item
		}
	}

	val current = displayed ?: return

	Box(modifier = modifier.clipToBounds()) {
		Crossfade(
			targetState = current,
			animationSpec = tween(durationMillis = 400),
			label = "hero",
		) { item ->
			val backdrop = item.itemBackdropImages.firstOrNull()
				?: item.parentBackdropImages.firstOrNull()
			val logo = item.itemImages[ImageType.LOGO] ?: item.parentImages[ImageType.LOGO]

			Box(modifier = Modifier.fillMaxSize()) {
				AsyncImage(
					// Request a screen-sized backdrop (not the full-res/4K original) so it
					// downloads and decodes fast on every focus change.
					url = backdrop?.getUrl(api, fillWidth = 1920, fillHeight = 1080),
					scaleType = ImageView.ScaleType.CENTER_CROP,
					modifier = Modifier.fillMaxSize(),
				)

				// Keep the upper "hero" band mostly clear so the artwork shows, then darken
				// toward the bottom where the rows sit so their titles stay readable.
				Box(
					modifier = Modifier
						.fillMaxSize()
						.background(
							Brush.verticalGradient(
								0f to Color.Black.copy(alpha = 0.5f),    // under the toolbar/logo
								0.12f to Color.Transparent,              // clear hero band (show the artwork)
								0.26f to Color.Black.copy(alpha = 0.3f), // rows begin
								0.55f to Color.Black.copy(alpha = 0.7f),
								1f to Color.Black.copy(alpha = 0.95f),   // dark base under the rows
							)
						)
				)
				// Left-edge scrim to anchor the logo and the first column of cards.
				Box(
					modifier = Modifier
						.fillMaxSize()
						.background(
							Brush.horizontalGradient(
								0f to Color.Black.copy(alpha = 0.55f),
								0.4f to Color.Transparent,
							)
						)
				)

				Column(
					modifier = Modifier
						.align(Alignment.TopStart)
						.padding(start = 48.dp, top = 80.dp, end = 48.dp)
				) {
					if (logo != null) {
						AsyncImage(
							url = logo.getUrl(api),
							blurHash = logo.blurHash,
							scaleType = ImageView.ScaleType.FIT_START,
							modifier = Modifier.height(50.dp),
						)
					} else {
						Text(
							text = item.name.orEmpty(),
							style = TextStyle(
								color = Color.White,
								fontSize = 34.sp,
								fontWeight = FontWeight.Bold,
							),
						)
					}
				}
			}
		}
	}
}
