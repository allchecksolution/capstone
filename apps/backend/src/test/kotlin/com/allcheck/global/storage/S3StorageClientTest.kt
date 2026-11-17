package com.allcheck.global.storage

import com.allcheck.support.S3TestFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.support.StaticListableBeanFactory
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.time.Duration

class S3StorageClientTest {
    @Test
    fun `서로 다른 버킷과 이미지 형식도 공통 클라이언트를 사용하고 덮어쓰기 정책은 호출자가 선택한다`() {
        // given
        S3TestFixtures.signer().use { signer ->
            val storage = storage(signer = signer)

            // when
            val original = storage.presignPut("work-photo-bucket", "photos/1", "image/jpeg", 123, S3TestFixtures.DIGEST, Duration.ofMinutes(1), createOnly = true)
            val profile = storage.presignPut("profile-bucket", "profiles/2", "image/png", 456, S3TestFixtures.DIGEST, Duration.ofMinutes(2), createOnly = false)

            // then
            assertThat(original.requiredHeaders.entries.first { it.key.equals("content-type", true) }.value).containsExactly("image/jpeg")
            assertThat(original.requiredHeaders.entries.first { it.key.equals("if-none-match", true) }.value).containsExactly("*")
            assertThat(profile.requiredHeaders.keys.none { it.equals("if-none-match", true) }).isTrue()
            assertThat(profile.url).contains("profile-bucket")
            assertThat(profile.toString()).doesNotContain("X-Amz", profile.url)
        }
    }

    @Test
    fun `메타데이터와 부분 읽기는 WAV나 특정 체크섬 필수 정책을 적용하지 않는다`() {
        // given
        val sdk = S3TestFixtures.fakeS3(
            header = {
                assertThat(it.range()).isEqualTo("bytes=2-5")
                assertThat(it.ifMatch()).isEqualTo("image-etag")
                byteArrayOf(1, 2, 3, 4)
            },
            head = { HeadObjectResponse.builder().contentType("image/jpeg").contentLength(456).eTag("image-etag").build() },
        )
        val storage = storage(sdk)

        // when
        val metadata = storage.metadata("photo-bucket", "photos/1")
        val bytes = storage.readRange("photo-bucket", "photos/1", 2, 5, metadata.eTag)

        // then
        assertThat(metadata.contentType).isEqualTo("image/jpeg")
        assertThat(metadata.checksumSha256).isNull()
        assertThat(bytes).containsExactly(1, 2, 3, 4)
    }

    @ParameterizedTest
    @CsvSource("404,NOT_FOUND", "412,PRECONDITION_FAILED", "416,INVALID_RANGE", "403,UNAVAILABLE", "503,UNAVAILABLE")
    fun `SDK 오류를 도메인 없는 기술 사유로 변환하고 민감한 원문을 전파하지 않는다`(status: Int, reason: StorageFailure) {
        // given
        val sdk = S3TestFixtures.fakeS3 { throw S3Exception.builder().statusCode(status).message("unsafe-signed-url-and-body").build() }

        // when
        val failure = assertThrows<StorageException> { storage(sdk).metadata("bucket", "key") }

        // then
        assertThat(failure.reason).isEqualTo(reason)
        assertThat(failure.message).doesNotContain("unsafe-signed")
        assertThat(failure.cause).isNull()
    }

    private fun storage(client: S3Client? = null, signer: S3Presigner? = null): S3StorageClient {
        val factory = StaticListableBeanFactory()
        client?.let { factory.addBean("client", it) }
        signer?.let { factory.addBean("signer", it) }
        return S3StorageClient(factory.getBeanProvider(S3Client::class.java), factory.getBeanProvider(S3Presigner::class.java))
    }
}
