package com.allcheck.reception.infrastructure.storage

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.global.storage.S3StorageClient
import com.allcheck.global.storage.SignedObjectUrl
import com.allcheck.global.storage.StorageException
import com.allcheck.global.storage.StorageFailure
import com.allcheck.reception.domain.vo.WavRecording
import com.allcheck.reception.exception.ReceptionErrorCode
import org.springframework.stereotype.Component
import java.util.Base64

/** 공통 S3 클라이언트에 녹음 형식·원본 불변·용도별 유효기간과 접수 오류 정책을 적용한다. */
@Component
class RecordingStorageClient(private val storage: S3StorageClient, private val urls: RecordingUrlProperties) {
    fun ensureSigningAvailable() = recordingOperation { storage.ensureSigningAvailable() }

    fun inspect(bucket: String, key: String): StoredRecordingMetadata = recordingOperation {
        val actual = storage.metadata(bucket, key)
        val checksum = try {
            Base64.getDecoder().decode(actual.checksumSha256 ?: throw BusinessException(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED))
        } catch (_: IllegalArgumentException) {
            throw BusinessException(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
        }
        val type = actual.contentType
        val size = actual.sizeBytes
        if (type.isNullOrBlank() || size == null || size <= 0 || checksum.size != 32 || !WavRecording.supportsContentType(type)) throw BusinessException(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
        val header = storage.readRange(bucket, key, 0, 11, actual.eTag)
        if (!WavRecording.hasWaveHeader(header)) throw BusinessException(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
        StoredRecordingMetadata(type, size, checksum)
    }

    fun upload(bucket: String, key: String, contentType: String, sizeBytes: Long, checksum: ByteArray): SignedObjectUrl = recordingOperation {
        WavRecording.validateMetadata("recording.wav", contentType)
        storage.presignPut(bucket, key, contentType, sizeBytes, checksum, urls.uploadUrlTtl, createOnly = true)
    }

    fun playback(bucket: String, key: String): SignedObjectUrl = recordingOperation { storage.presignGet(bucket, key, urls.playbackUrlTtl) }

    fun aiDownload(bucket: String, key: String): SignedObjectUrl = recordingOperation { storage.presignGet(bucket, key, urls.aiDownloadUrlTtl) }

    private fun <T> recordingOperation(action: () -> T): T = try {
        action()
    } catch (failure: StorageException) {
        val code = if (failure.reason == StorageFailure.UNAVAILABLE) ReceptionErrorCode.RECORDING_STORAGE_UNAVAILABLE else GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED
        throw BusinessException(code)
    }
}

class StoredRecordingMetadata(val contentType: String, val sizeBytes: Long, checksum: ByteArray) {
    private val digest = checksum.copyOf()
    val checksum: ByteArray get() = digest.copyOf()
}
