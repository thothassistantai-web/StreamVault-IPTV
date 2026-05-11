package com.streamvault.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.streamvault.data.local.entity.XtreamLiveOnboardingStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface XtreamLiveOnboardingDao {
    @Query("SELECT * FROM xtream_live_onboarding_state WHERE provider_id = :providerId")
    suspend fun getByProvider(providerId: Long): XtreamLiveOnboardingStateEntity?

    @Query("SELECT * FROM xtream_live_onboarding_state WHERE provider_id = :providerId AND completed_at IS NULL")
    suspend fun getIncompleteByProvider(providerId: Long): XtreamLiveOnboardingStateEntity?

    @Query("SELECT * FROM xtream_live_onboarding_state WHERE completed_at IS NULL ORDER BY updated_at ASC")
    suspend fun getIncomplete(): List<XtreamLiveOnboardingStateEntity>

    @Query("SELECT * FROM xtream_live_onboarding_state WHERE completed_at IS NULL ORDER BY updated_at ASC")
    fun observeIncomplete(): Flow<List<XtreamLiveOnboardingStateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: XtreamLiveOnboardingStateEntity)

    @Query("DELETE FROM xtream_live_onboarding_state WHERE provider_id = :providerId")
    suspend fun delete(providerId: Long)
}