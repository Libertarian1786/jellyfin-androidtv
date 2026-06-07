package org.jellyfin.androidtv.ui.home

import android.widget.ImageView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.seconds

/**
 * A rotating full-width hero banner ("Media Bar") at the top of the home screen.
 * Cycles through recently added movies that have a backdrop image, showing the
 * backdrop with the title logo (or name) overlaid.
 */
@Composable
fun HomeHeroCarousel(modifier: Modifier = Modifier) {
	val api = koinInject<ApiClient>()
	var items by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }

	LaunchedEffect(Unit) {
		items = runCatching {
			api.itemsApi.getItems(
				includeItemTypes = setOf(BaseItemKind.MOVIE),
				recursive = true,
				sortBy = setOf(ItemSortBy.DATE_CREATED),
				sortOrder = setOf(SortOrder.DESCENDING),
				imageTypes = setOf(ImageType.BACKDROP),
				enableImageTypes = setOf(ImageType.BACKDROP, ImageType.LOGO),
				limit = 15,
			).content.items.orEmpty()
		}.getOrDefault(emptyList())
			.filter { !it.backdropImageTags.isNullOrEmpty() }
	}

	if (items.isEmpty()) return

	var index by remember { mutableStateOf(0) }
	LaunchedEffect(items.size) {
		while (true) {
			delay(9.seconds)
			if (items.isNotEmpty()) index = (index + 1) % items.size
		}
	}

	val current = items.getOrNull(index) ?: return

	Box(
		modifier = modifier
			.fillMaxWidth()
			.height(120.dp)
			.clipToBounds()
	) {
		Crossfade(
			targetState = current,
			animationSpec = tween(durationMillis = 800),
			label = "hero",
		) { item ->
			val backdrop = item.itemBackdropImages.firstOrNull()
			val logo = item.itemImages[ImageType.LOGO]

			Box(modifier = Modifier.fillMaxSize()) {
				AsyncImage(
					url = backdrop?.getUrl(api),
					blurHash = backdrop?.blurHash,
					scaleType = ImageView.ScaleType.CENTER_CROP,
					modifier = Modifier.fillMaxSize(),
				)

				// Darken the bottom edge for legibility of the title.
				Box(
					modifier = Modifier
						.fillMaxSize()
						.background(
							Brush.verticalGradient(
								0.45f to Color.Transparent,
								1f to Color.Black.copy(alpha = 0.9f),
							)
						)
				)

				Column(
					modifier = Modifier
						.align(Alignment.BottomStart)
						.padding(start = 48.dp, end = 48.dp, bottom = 28.dp)
				) {
					if (logo != null) {
						AsyncImage(
							url = logo.getUrl(api),
							blurHash = logo.blurHash,
							scaleType = ImageView.ScaleType.FIT_START,
							modifier = Modifier.height(80.dp),
						)
					} else {
						Text(
							text = item.name.orEmpty(),
							style = TextStyle(
								color = Color.White,
								fontSize = 36.sp,
								fontWeight = FontWeight.Bold,
							),
						)
					}
				}
			}
		}
	}
}
