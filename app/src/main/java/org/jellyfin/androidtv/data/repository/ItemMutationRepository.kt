package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.Instant

interface ItemMutationRepository {
	suspend fun setFavorite(item: UUID, favorite: Boolean): UserItemDataDto
	suspend fun setPlayed(item: UUID, played: Boolean): UserItemDataDto

	/** Drop the resume point so the item leaves Continue Watching (watched state is untouched). */
	suspend fun clearResume(item: UUID): UserItemDataDto
}

class ItemMutationRepositoryImpl(
	private val api: ApiClient,
	private val dataRefreshService: DataRefreshService,
) : ItemMutationRepository {
	override suspend fun setFavorite(item: UUID, favorite: Boolean): UserItemDataDto {
		val response by when {
			favorite -> withContext(Dispatchers.IO) { api.userLibraryApi.markFavoriteItem(itemId = item) }
			else -> withContext(Dispatchers.IO) { api.userLibraryApi.unmarkFavoriteItem(itemId = item) }
		}

		dataRefreshService.lastFavoriteUpdate = Instant.now()
		return response
	}

	override suspend fun setPlayed(item: UUID, played: Boolean): UserItemDataDto {
		val response by when {
			played -> withContext(Dispatchers.IO) { api.playStateApi.markPlayedItem(itemId = item) }
			else -> withContext(Dispatchers.IO) { api.playStateApi.markUnplayedItem(itemId = item) }
		}

		return response
	}

	override suspend fun clearResume(item: UUID): UserItemDataDto {
		val response = withContext(Dispatchers.IO) {
			api.itemsApi.updateItemUserData(itemId = item, data = UpdateUserItemDataDto(playbackPositionTicks = 0)).content
		}

		// Continue Watching refreshes on playback changes
		val now = Instant.now()
		dataRefreshService.lastPlayback = now
		dataRefreshService.lastMoviePlayback = now
		dataRefreshService.lastTvPlayback = now
		return response
	}
}
