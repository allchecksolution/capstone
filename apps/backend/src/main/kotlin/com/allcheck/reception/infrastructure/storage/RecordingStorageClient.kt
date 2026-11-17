package com.allcheck.reception.infrastructure.storage

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.domain.vo.WavRecording
import com.allcheck.reception.exception.ReceptionErrorCode
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ChecksumMode
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** S3 객체만 다룬다. 서명 URL·SDK 예외 원문은 저장하거나 로그에 남기지 않는다. */
@Component
class RecordingStorageClient(
    private val clients: ObjectProvider<S3Client>,
    private val signers: ObjectProvider<S3Presigner>,
    private val urls: RecordingUrlProperties,
) {
    fun ensureSigningAvailable() {
        if (signers.ifAvailable == null) unavailable()
    }

    fun inspect(bucket: String, key: String): StoredRecordingMetadata {
        val client = clients.ifAvailable ?: unavailable()
        val result = try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).checksumMode(ChecksumMode.ENABLED).build())
        } catch (failure: S3Exception) {
            if (failure.statusCode() == 404) throw BusinessException(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
            unavailable()
        } catch (_: SdkClientException) {
            unavailable()
        }
        val checksum = try {
            Base64.getDecoder().decode(result.checksumSHA256() ?: throw BusinessException(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED))
        } catch (_: IllegalArgumentException) {
            throw BusinessException(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
        }
        val type = result.contentType()
        val size = result.contentLength()
        if (type.isNullOrBlank() || size == null || size <= 0 || checksum.size != 32) throw BusinessException(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
        if (!WavRecording.supportsContentType(type)) throw BusinessException(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
        val header = try {
            client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=0-11").ifMatch(result.eTag()).build()).asByteArray()
        } catch (failure: S3Exception) {
            if (failure.statusCode() in setOf(404, 412, 416)) throw BusinessException(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
            unavailable()
        } catch (_: SdkClientException) {
            unavailable()
        }
        if (!WavRecording.hasWaveHeader(header)) throw BusinessException(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
        return StoredRecordingMetadata(type, size, checksum)
    }

    fun upload(bucket: String, key: String, contentType: String, sizeBytes: Long, checksum: ByteArray): SignedRecordingUrl = signing {
        WavRecording.validateMetadata("recording.wav", contentType)
        require(sizeBytes > 0 && checksum.size == 32)
        val put = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).contentLength(sizeBytes)
            .ifNoneMatch("*").checksumSHA256(Base64.getEncoder().encodeToString(checksum)).build()
        val signed = it.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(urls.uploadUrlTtl).putObjectRequest(put).build())
        SignedRecordingUrl(signed.url().toExternalForm(), signed.expiration(), signed.signedHeaders().filterKeys { name -> !name.equals("host", true) })
    }

    fun playback(bucket: String, key: String): SignedRecordingUrl = download(bucket, key, urls.playbackUrlTtl)

    fun aiDownload(bucket: String, key: String): SignedRecordingUrl = download(bucket, key, urls.aiDownloadUrlTtl)

    private fun download(bucket: String, key: String, ttl: Duration): SignedRecordingUrl = signing {
        val get = GetObjectRequest.builder().bucket(bucket).key(key).build()
        val signed = it.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(get).build())
        SignedRecordingUrl(signed.url().toExternalForm(), signed.expiration(), emptyMap())
    }

    private fun <T> signing(action: (S3Presigner) -> T): T {
        val signer = signers.ifAvailable ?: unavailable()
        return try {
            action(signer)
        } catch (_: SdkClientException) {
            unavailable()
        }
    }

    private fun unavailable(): Nothing = throw BusinessException(ReceptionErrorCode.RECORDING_STORAGE_UNAVAILABLE)
}

class SignedRecordingUrl(val url: String, val expiresAt: Instant, val requiredHeaders: Map<String, List<String>>)

class StoredRecordingMetadata(val contentType: String, val sizeBytes: Long, checksum: ByteArray) {
    private val digest = checksum.copyOf()
    val checksum: ByteArray get() = digest.copyOf()
}
