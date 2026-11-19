package com.allcheck.operations.coordination.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.operations.ActorRole
import com.allcheck.operations.OperationsActor
import com.allcheck.operations.coordination.presentation.dto.PhotoAccessResponse
import com.allcheck.operations.coordination.presentation.dto.PhotoUploadResponse
import com.allcheck.operations.work.PhotoMetadata
import com.allcheck.operations.work.PhotoStorageOperations
import org.springframework.stereotype.Service

@Service
class OperationsPhotos(private val queries: OperationsQueries, private val transactions: OperationsReportTransactions, private val storage: PhotoStorageOperations) {
    fun reserve(actor: OperationsActor, workId: Long, key: String, metadata: PhotoMetadata): Pair<PhotoUploadResponse, Boolean> {
        val work = queries.work(actor, workId)
        requireCurrent(actor, work.canTransition)
        storage.available()
        val result = transactions.reserve(actor, workId, key, metadata, storage.bucket())
        return grant(actor, workId, result.value.resourceId) to result.replayed
    }
    fun grant(actor: OperationsActor, workId: Long, photoId: Long): PhotoUploadResponse {
        val work = queries.work(actor, workId)
        requireCurrent(actor, work.canTransition)
        val photo = queries.photo(actor, workId, photoId)
        if (photo.driverId != actor.id) throw BusinessException(GlobalErrorCode.FILE_NOT_READY)
        val signed = storage.upload(photo)
        return PhotoUploadResponse("photo_$photoId", "work_$workId", photo.phase, signed.url, signed.expiresAt, "PUT", signed.requiredHeaders)
    }
    fun complete(actor: OperationsActor, workId: Long, photoId: Long, key: String): CommandResult {
        val photo = queries.photo(actor, workId, photoId)
        if (actor.role != ActorRole.DRIVER || photo.driverId != actor.id) throw BusinessException(GlobalErrorCode.FILE_NOT_READY)
        if (photo.status != "UPLOADED") {
            requireCurrent(actor, queries.work(actor, workId).canTransition)
            storage.verify(photo)
        }
        return transactions.completePhoto(actor, workId, photoId, key)
    }
    fun access(actor: OperationsActor, workId: Long, photoId: Long): PhotoAccessResponse {
        val photo = queries.photo(actor, workId, photoId)
        val signed = storage.access(photo)
        return PhotoAccessResponse("photo_$photoId", signed.url, signed.expiresAt, "GET", photo.contentType)
    }
    private fun requireCurrent(actor: OperationsActor, current: Boolean) {
        if (actor.role != ActorRole.DRIVER || !current) throw BusinessException(GlobalErrorCode.FILE_NOT_READY)
    }
}
