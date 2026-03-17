package com.streamvault.domain.manager

import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.RecordingStorageState
import com.streamvault.domain.model.Result
import kotlinx.coroutines.flow.Flow

interface RecordingManager {
    fun observeRecordingItems(): Flow<List<RecordingItem>>
    fun observeStorageState(): Flow<RecordingStorageState>

    suspend fun startManualRecording(request: RecordingRequest): Result<RecordingItem>
    suspend fun scheduleRecording(request: RecordingRequest): Result<RecordingItem>
    suspend fun stopRecording(recordingId: String): Result<Unit>
    suspend fun cancelRecording(recordingId: String): Result<Unit>
    suspend fun deleteRecording(recordingId: String): Result<Unit>
}
