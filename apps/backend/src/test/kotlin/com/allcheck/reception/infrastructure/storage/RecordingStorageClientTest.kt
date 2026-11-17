package com.allcheck.reception.infrastructure.storage

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.exception.ReceptionErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.StaticListableBeanFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.ChecksumMode
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.lang.reflect.Proxy
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Base64

class RecordingStorageClientTest {
    @Test
    fun `실제 SDK 서명에 조건부 업로드와 MIME 길이 SHA256을 포함하고 GET 만료를 구분한다`() {
        // given: 오프라인 서명용 가짜 자격증명이며 AWS에 연결하지 않는다.
        signer().use { signer ->
            val storage = storage(signer = signer)
            val before = Instant.now()

            // when
            val upload = storage.upload("test-recordings", "recordings/1/test", "audio/wav", 1024, DIGEST)
            val playback = storage.playback("test-recordings", "recordings/1/test")
            val ai = storage.aiDownload("test-recordings", "recordings/1/test")

            // then
            val headers = upload.requiredHeaders.mapKeys { it.key.lowercase() }
            assertThat(headers["if-none-match"]).containsExactly("*")
            assertThat(headers["content-type"]).containsExactly("audio/wav")
            assertThat(headers["content-length"]).containsExactly("1024")
            assertThat(headers["x-amz-checksum-sha256"]).containsExactly(Base64.getEncoder().encodeToString(DIGEST))
            assertThat(URI(upload.url).scheme).isEqualTo("https")
            assertThat(upload.expiresAt).isBetween(before.plusSeconds(900), Instant.now().plusSeconds(900))
            assertThat(playback.expiresAt).isBetween(before.plusSeconds(600), Instant.now().plusSeconds(600))
            assertThat(ai.expiresAt).isBetween(before.plusSeconds(300), Instant.now().plusSeconds(300))
            assertThat(upload.toString()).doesNotContain("X-Amz", "test-secret", upload.url)
        }
    }

    @Test
    fun `HEAD의 실제 메타데이터와 체크섬을 가져오며 반환 배열은 변경할 수 없다`() {
        // given
        val client = fakeS3 { request ->
            assertThat(request.bucket()).isEqualTo("test-recordings")
            assertThat(request.key()).isEqualTo("recordings/1/test")
            assertThat(request.checksumMode()).isEqualTo(ChecksumMode.ENABLED)
            HeadObjectResponse.builder().contentType("audio/wav").contentLength(1024).checksumSHA256(Base64.getEncoder().encodeToString(DIGEST)).build()
        }

        // when
        val actual = storage(client).inspect("test-recordings", "recordings/1/test")
        actual.checksum[0] = 99

        // then
        assertThat(actual.contentType).isEqualTo("audio/wav")
        assertThat(actual.sizeBytes).isEqualTo(1024)
        assertThat(actual.checksum).isEqualTo(DIGEST)
    }

    @Test
    fun `객체 없음과 저장소 장애를 구분하고 SDK 오류 원문을 노출하지 않는다`() {
        // given
        val missing = fakeS3 { throw S3Exception.builder().statusCode(404).message("unsafe-signed-url").build() }
        val failed = fakeS3 { throw S3Exception.builder().statusCode(403).message("unsafe-signed-url").build() }

        // when & then
        assertThat(assertThrows<BusinessException> { storage(missing).inspect("bucket", "key") }.errorCode).isEqualTo(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
        val failure = assertThrows<BusinessException> { storage(failed).inspect("bucket", "key") }
        assertThat(failure.errorCode).isEqualTo(ReceptionErrorCode.RECORDING_STORAGE_UNAVAILABLE)
        assertThat(failure.message).doesNotContain("unsafe-signed-url")
        assertThat(failure.cause).isNull()
        assertThat(assertThrows<BusinessException> { storage().playback("bucket", "key") }.errorCode).isEqualTo(ReceptionErrorCode.RECORDING_STORAGE_UNAVAILABLE)
    }

    @Test
    fun `누락되거나 잘못된 실제 SHA256을 업로드 완료로 인정하지 않는다`() {
        // when & then
        for (checksum in listOf(null, "not-base64", Base64.getEncoder().encodeToString(ByteArray(8)))) {
            val client = fakeS3 { HeadObjectResponse.builder().contentType("audio/wav").contentLength(1024).checksumSHA256(checksum).build() }
            assertThat(assertThrows<BusinessException> { storage(client).inspect("bucket", "key") }.errorCode).isEqualTo(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
        }
    }

    @Test
    fun `확장자와 MIME이 WAV여도 실제 객체 헤더가 다르면 검증 완료할 수 없다`() {
        // given
        val client = fakeS3(
            head = { HeadObjectResponse.builder().contentType("audio/wav").contentLength(1024).eTag("test-etag").checksumSHA256(Base64.getEncoder().encodeToString(DIGEST)).build() },
            header = { request ->
                assertThat(request.range()).isEqualTo("bytes=0-11")
                assertThat(request.ifMatch()).isEqualTo("test-etag")
                "ID3 NOT WAVE".toByteArray()
            },
        )

        // when & then
        assertThat(assertThrows<BusinessException> { storage(client).inspect("bucket", "key") }.errorCode).isEqualTo(GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
    }

    private fun storage(client: S3Client? = null, signer: S3Presigner? = null): RecordingStorageClient {
        val factory = StaticListableBeanFactory()
        client?.let { factory.addBean("client", it) }
        signer?.let { factory.addBean("signer", it) }
        return RecordingStorageClient(factory.getBeanProvider(S3Client::class.java), factory.getBeanProvider(S3Presigner::class.java), RecordingUrlProperties(Duration.ofMinutes(15), Duration.ofMinutes(10), Duration.ofMinutes(5)))
    }

    companion object {
        val DIGEST = ByteArray(32) { 1 }

        fun signer(onResolve: () -> Unit = {}): S3Presigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
            .credentialsProvider(
                AwsCredentialsProvider {
                    onResolve()
                    AwsBasicCredentials.create("test-access-key", "test-secret-key")
                },
            ).build()

        fun fakeS3(header: (GetObjectRequest) -> ByteArray = { "RIFF0000WAVE".toByteArray(Charsets.US_ASCII) }, head: (HeadObjectRequest) -> HeadObjectResponse): S3Client = Proxy.newProxyInstance(S3Client::class.java.classLoader, arrayOf(S3Client::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "headObject" -> head(arguments[0] as HeadObjectRequest)
                "getObjectAsBytes" -> ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), header(arguments[0] as GetObjectRequest))
                "close" -> null
                "serviceName" -> "s3"
                "toString" -> "OfflineTestS3"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments[0]
                else -> error("Unexpected S3 test operation.")
            }
        } as S3Client
    }
}
