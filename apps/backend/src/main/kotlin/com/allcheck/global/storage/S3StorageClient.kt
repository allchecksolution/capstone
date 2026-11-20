package com.allcheck.global.storage

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

/** 도메인·파일 형식·접근 권한을 알지 않는 S3 기술 처리. URL과 SDK 예외 원문은 저장/로그에 남기지 않는다. */
@Component
class S3StorageClient(private val clients: ObjectProvider<S3Client>, private val signers: ObjectProvider<S3Presigner>) {
    fun ensureSigningAvailable() {
        if (signers.ifAvailable == null) throw StorageException(StorageFailure.UNAVAILABLE)
    }

    fun metadata(bucket: String, key: String): ObjectMetadata = sdk {
        val response = client().headObject(HeadObjectRequest.builder().bucket(bucket).key(key).checksumMode(ChecksumMode.ENABLED).build())
        ObjectMetadata(response.contentType(), response.contentLength(), response.checksumSHA256(), response.eTag())
    }

    fun readRange(bucket: String, key: String, first: Long, last: Long, eTag: String? = null): ByteArray = sdk {
        require(first >= 0 && last >= first)
        client().getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=$first-$last").ifMatch(eTag).build()).asByteArray()
    }

    fun presignPut(bucket: String, key: String, contentType: String, sizeBytes: Long, checksum: ByteArray, ttl: Duration, createOnly: Boolean): SignedObjectUrl = sdk {
        validateTtl(ttl)
        require(sizeBytes > 0 && checksum.size == 32)
        val request = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).contentLength(sizeBytes)
            .checksumSHA256(Base64.getEncoder().encodeToString(checksum)).ifNoneMatch(if (createOnly) "*" else null).build()
        val signed = signer().presignPutObject(PutObjectPresignRequest.builder().signatureDuration(ttl).putObjectRequest(request).build())
        SignedObjectUrl(signed.url().toExternalForm(), signed.expiration(), signed.signedHeaders().filterKeys { !it.equals("host", true) })
    }

    fun presignGet(bucket: String, key: String, ttl: Duration): SignedObjectUrl = sdk {
        validateTtl(ttl)
        val request = GetObjectRequest.builder().bucket(bucket).key(key).build()
        val signed = signer().presignGetObject(GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(request).build())
        SignedObjectUrl(signed.url().toExternalForm(), signed.expiration(), emptyMap())
    }

    private fun validateTtl(ttl: Duration) {
        require(ttl >= Duration.ofSeconds(1) && ttl <= Duration.ofDays(7) && ttl.nano == 0) { "S3 URL TTL must be whole seconds between 1 second and 7 days." }
    }

    private fun client() = clients.ifAvailable ?: throw StorageException(StorageFailure.UNAVAILABLE)
    private fun signer() = signers.ifAvailable ?: throw StorageException(StorageFailure.UNAVAILABLE)

    private fun <T> sdk(action: () -> T): T = try {
        action()
    } catch (failure: S3Exception) {
        val reason = when (failure.statusCode()) {
            404 -> StorageFailure.NOT_FOUND
            412 -> StorageFailure.PRECONDITION_FAILED
            416 -> StorageFailure.INVALID_RANGE
            else -> StorageFailure.UNAVAILABLE
        }
        throw StorageException(reason)
    } catch (_: SdkClientException) {
        throw StorageException(StorageFailure.UNAVAILABLE)
    }
}

class ObjectMetadata(val contentType: String?, val sizeBytes: Long?, val checksumSha256: String?, val eTag: String?)

class SignedObjectUrl(val url: String, val expiresAt: Instant, headers: Map<String, List<String>>) {
    private val storedHeaders = headers.mapValues { it.value.toList() }
    val requiredHeaders: Map<String, List<String>> get() = storedHeaders.mapValues { it.value.toList() }
}

enum class StorageFailure { NOT_FOUND, PRECONDITION_FAILED, INVALID_RANGE, UNAVAILABLE }

/** 안전한 기술 분류만 전달한다. SDK cause에 포함될 수 있는 서명 URL·응답 본문은 노출하지 않는다. */
class StorageException(val reason: StorageFailure) : RuntimeException("Object storage operation failed: ${reason.name}")
