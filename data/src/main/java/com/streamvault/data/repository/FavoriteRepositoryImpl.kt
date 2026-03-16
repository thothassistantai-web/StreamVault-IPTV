package com.streamvault.data.repository

import com.streamvault.data.local.dao.FavoriteDao
import com.streamvault.data.local.dao.VirtualGroupDao
import com.streamvault.data.local.entity.CategoryCount
import com.streamvault.data.mapper.toDomain
import com.streamvault.data.mapper.toEntity
import com.streamvault.domain.model.*
import com.streamvault.domain.repository.FavoriteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteRepositoryImpl @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val virtualGroupDao: VirtualGroupDao
) : FavoriteRepository {

    override fun getFavorites(contentType: ContentType?): Flow<List<Favorite>> {
        val flow = if (contentType != null) {
            favoriteDao.getGlobalByType(contentType.name)
        } else {
            favoriteDao.getAllGlobal()
        }
        return flow.map { entities -> entities.map { it.toDomain() } }
    }

    @Deprecated("Use getFavorites(contentType) instead", ReplaceWith("getFavorites(contentType)"))
    override fun getAllFavorites(contentType: ContentType): Flow<List<Favorite>> =
        favoriteDao.getAllByType(contentType.name)
            .map { entities -> entities.map { it.toDomain() } }

    override fun getFavoritesByGroup(groupId: Long): Flow<List<Favorite>> =
        favoriteDao.getByGroup(groupId).map { entities -> entities.map { it.toDomain() } }

    override fun getGroups(contentType: ContentType): Flow<List<VirtualGroup>> =
        virtualGroupDao.getByType(contentType.name).map { entities -> entities.map { it.toDomain() } }

    override fun getGlobalFavoriteCount(contentType: ContentType): Flow<Int> =
        favoriteDao.getGlobalFavoriteCount(contentType.name)

    override fun getGroupFavoriteCounts(contentType: ContentType): Flow<Map<Long, Int>> =
        favoriteDao.getGroupFavoriteCounts(contentType.name)
            .map { list -> list.associate { it.categoryId to it.item_count } }

    override suspend fun addFavorite(
        contentId: Long,
        contentType: ContentType,
        groupId: Long?
    ): Result<Unit> = try {
        val maxPos = favoriteDao.getMaxPosition(groupId) ?: -1
        val favorite = Favorite(
            contentId = contentId,
            contentType = contentType,
            position = maxPos + 1,
            groupId = groupId
        )
        favoriteDao.insert(favorite.toEntity())
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to add favorite: ${e.message}", e)
    }

    override suspend fun removeFavorite(contentId: Long, contentType: ContentType, groupId: Long?): Result<Unit> = try {
        favoriteDao.delete(contentId, contentType.name, groupId)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to remove favorite: ${e.message}", e)
    }

    override suspend fun reorderFavorites(favorites: List<Favorite>): Result<Unit> = try {
        val entities = favorites.mapIndexed { index, fav ->
            fav.copy(position = index).toEntity()
        }
        favoriteDao.updateAll(entities)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to reorder favorites: ${e.message}", e)
    }

    // Checks if content is in Global Favorites (groupId = null)
    override suspend fun isFavorite(contentId: Long, contentType: ContentType): Boolean =
        favoriteDao.get(contentId, contentType.name, null) != null

    override suspend fun getGroupMemberships(contentId: Long, contentType: ContentType): List<Long> =
        favoriteDao.getGroupMemberships(contentId, contentType.name)

    override suspend fun createGroup(name: String, iconEmoji: String?, contentType: ContentType): Result<VirtualGroup> = try {
        val id = virtualGroupDao.insert(
            com.streamvault.data.local.entity.VirtualGroupEntity(
                name = name,
                iconEmoji = iconEmoji,
                contentType = contentType.name
            )
        )
        Result.success(VirtualGroup(id = id, name = name, iconEmoji = iconEmoji, contentType = contentType))
    } catch (e: Exception) {
        Result.error("Failed to create group: ${e.message}", e)
    }

    override suspend fun deleteGroup(groupId: Long): Result<Unit> = try {
        virtualGroupDao.delete(groupId)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to delete group: ${e.message}", e)
    }

    override suspend fun renameGroup(groupId: Long, newName: String): Result<Unit> = try {
        virtualGroupDao.rename(groupId, newName)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.error("Failed to rename group: ${e.message}", e)
    }
}
