package com.allcheck.identity.infrastructure.token

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class RefreshTokenProviderTest {
    @Test
    fun `256비트 난수를 URL 안전한 패딩 없는 토큰으로 변환한다`() {
        // given
        val random = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                assertThat(bytes).hasSize(32)
                bytes.indices.forEach { bytes[it] = it.toByte() }
            }
        }

        // when
        val token = RefreshTokenProvider(random).generate()

        // then
        assertThat(token).isEqualTo("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
    }

    @Test
    fun `원문 대신 표준 SHA-256 hex 다이제스트를 생성한다`() {
        // given & when
        val hash = RefreshTokenProvider().hash("abc")

        // then
        assertThat(hash.value).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(hash.toString()).doesNotContain(hash.value)
    }
}
