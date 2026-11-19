package com.allcheck.operations.infrastructure

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.global.storage.S3StorageClient
import com.allcheck.operations.PhotoPhase
import com.allcheck.operations.exception.OperationsErrorCode
import com.allcheck.operations.work.PhotoSnapshot
import com.allcheck.operations.work.infrastructure.storage.WorkPhotoStorage
import com.allcheck.support.S3TestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.StaticListableBeanFactory
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.time.Duration
import java.util.Base64

class WorkPhotoStorageTest {
    @Test
    fun `오프라인 서명은 조건부 PUT과 MIME 길이 체크섬을 고정한다`() {
        // given
        S3TestFixtures.signer().use { signer ->
            val storage = storage(signer = signer)
            // when
            val signed = storage.upload(photo())
            // then
            val headers = signed.requiredHeaders.mapKeys { it.key.lowercase() }
            assertThat(headers["if-none-match"]).containsExactly("*")
            assertThat(headers["content-type"]).containsExactly("image/jpeg")
            assertThat(headers["content-length"]).containsExactly("100")
            assertThat(headers["x-amz-checksum-sha256"]).containsExactly(photo().checksumBase64)
            assertThat(assertThrows<BusinessException> { storage.access(photo()) }.errorCode).isEqualTo(GlobalErrorCode.FILE_NOT_READY)
        }
    }

    @Test
    fun `실제 객체 크기 체크섬 MIME 및 시그니처가 모두 일치해야 사진을 인정한다`() {
        // given
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        val correct = head()
        val client = S3TestFixtures.fakeS3(header = { request ->
            assertThat(request.range()).isEqualTo("bytes=0-15")
            assertThat(request.ifMatch()).isEqualTo("etag-test")
            jpeg
        }, head = { correct })
        // when & then
        storage(client).verify(photo())
        listOf(head(size = 101), head(type = "image/png"), head(checksum = "invalid")).forEach { mismatch ->
            val wrong = S3TestFixtures.fakeS3(header = { jpeg }, head = { mismatch })
            assertThat(assertThrows<BusinessException> { storage(wrong).verify(photo()) }.errorCode).isEqualTo(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
        }
        val png = S3TestFixtures.fakeS3(header = { byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) }, head = { correct })
        assertThat(assertThrows<BusinessException> { storage(png).verify(photo()) }.errorCode).isEqualTo(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
    }

    @Test
    fun `미업로드와 원격 장애를 구분하고 원격 오류 내용은 노출하지 않는다`() {
        // given
        val missing = S3TestFixtures.fakeS3 { throw S3Exception.builder().statusCode(404).message("private-url").build() }
        val denied = S3TestFixtures.fakeS3 { throw S3Exception.builder().statusCode(403).message("private-url").build() }
        // when & then
        assertThat(assertThrows<BusinessException> { storage(missing).verify(photo()) }.errorCode).isEqualTo(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
        val failure = assertThrows<BusinessException> { storage(denied).verify(photo()) }
        assertThat(failure.errorCode).isEqualTo(OperationsErrorCode.PHOTO_STORAGE_UNAVAILABLE)
        assertThat(failure.cause).isNull()
        assertThat(failure.message).doesNotContain("private-url")
    }

    private fun photo() = PhotoSnapshot(1, 1, 10, PhotoPhase.BEFORE, "test-photos", "works/1/test", "image/jpeg", 100, Base64.getEncoder().encodeToString(S3TestFixtures.DIGEST), "PENDING", null)
    private fun head(type: String = "image/jpeg", size: Long = 100, checksum: String = photo().checksumBase64) = HeadObjectResponse.builder().contentType(type).contentLength(size).checksumSHA256(checksum).eTag("etag-test").build()
    private fun storage(client: S3Client? = null, signer: S3Presigner? = null): WorkPhotoStorage {
        val beans = StaticListableBeanFactory()
        client?.let { beans.addBean("s3", it) }
        signer?.let { beans.addBean("signer", it) }
        return WorkPhotoStorage(S3StorageClient(beans.getBeanProvider(S3Client::class.java), beans.getBeanProvider(S3Presigner::class.java)), "test-photos", Duration.ofMinutes(15), Duration.ofMinutes(10))
    }
}
