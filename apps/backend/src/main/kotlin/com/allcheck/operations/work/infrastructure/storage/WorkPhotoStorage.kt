package com.allcheck.operations.work.infrastructure.storage

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.global.storage.S3StorageClient
import com.allcheck.global.storage.StorageException
import com.allcheck.global.storage.StorageFailure
import com.allcheck.operations.exception.OperationsErrorCode
import com.allcheck.operations.work.PhotoSnapshot
import com.allcheck.operations.work.PhotoStorageOperations
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Base64

@Component
class WorkPhotoStorage(
    private val s3: S3StorageClient,
    @Value("\${operations.photos.bucket:}") private val bucket: String,
    @Value("\${operations.photos.upload-url-ttl:15m}") private val uploadTtl: Duration,
    @Value("\${operations.photos.access-url-ttl:10m}") private val accessTtl: Duration,
) : PhotoStorageOperations {
    init {
        require(listOf(uploadTtl, accessTtl).all { it >= Duration.ofSeconds(1) && it <= Duration.ofDays(7) && it.nano == 0 })
    }
    override fun bucket() = bucket
    override fun available() = storage {
        if (bucket.isBlank()) throw BusinessException(OperationsErrorCode.PHOTO_STORAGE_UNAVAILABLE)
        s3.ensureSigningAvailable()
    }
    override fun upload(photo: PhotoSnapshot) = storage {
        if (photo.status != "PENDING") throw BusinessException(GlobalErrorCode.FILE_ALREADY_UPLOADED)
        s3.presignPut(photo.bucket, photo.objectKey, photo.contentType, photo.sizeBytes, Base64.getDecoder().decode(photo.checksumBase64), uploadTtl, true)
    }
    override fun access(photo: PhotoSnapshot) = storage {
        if (photo.status != "UPLOADED") throw BusinessException(GlobalErrorCode.FILE_NOT_READY)
        s3.presignGet(photo.bucket, photo.objectKey, accessTtl)
    }
    override fun verify(photo: PhotoSnapshot) = storage {
        val head = s3.metadata(photo.bucket, photo.objectKey)
        if (head.contentType != photo.contentType || head.sizeBytes != photo.sizeBytes || head.checksumSha256 != photo.checksumBase64) throw BusinessException(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
        val bytes = s3.readRange(photo.bucket, photo.objectKey, 0, minOf(15, photo.sizeBytes - 1), head.eTag)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        val signature = if (photo.contentType == "image/png") png else jpeg
        if (bytes.size < signature.size || !bytes.copyOfRange(0, signature.size).contentEquals(signature)) throw BusinessException(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
    }
    private fun <T> storage(action: () -> T): T = try {
        action()
    } catch (failure: StorageException) {
        if (failure.reason in setOf(StorageFailure.NOT_FOUND, StorageFailure.PRECONDITION_FAILED, StorageFailure.INVALID_RANGE)) throw BusinessException(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
        throw BusinessException(OperationsErrorCode.PHOTO_STORAGE_UNAVAILABLE)
    }
}
