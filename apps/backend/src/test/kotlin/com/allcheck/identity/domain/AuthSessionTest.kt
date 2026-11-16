package com.allcheck.identity.domain

import com.allcheck.identity.domain.entity.AuthSession
import com.allcheck.identity.domain.vo.RefreshTokenHash
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant

class AuthSessionTest {
    private val createdAt = Instant.parse("2026-11-16T00:00:00Z")
    private val expiresAt = createdAt.plus(Duration.ofDays(14))
    private val initialHash = RefreshTokenHash("a".repeat(64))
    private val replacementHash = RefreshTokenHash("b".repeat(64))

    @Test
    fun `세션은 생성 시각부터 만료 직전까지만 유효하다`() {
        // given
        val session = session()

        // when & then
        assertThat(session.isActiveAt(createdAt.minusNanos(1))).isFalse()
        assertThat(session.isActiveAt(createdAt)).isTrue()
        assertThat(session.isActiveAt(expiresAt.minusNanos(1))).isTrue()
        assertThat(session.isActiveAt(expiresAt)).isFalse()
    }

    @Test
    fun `갱신은 이전 토큰을 무효화하고 세션 절대 만료를 연장하지 않는다`() {
        // given
        val session = session()
        val refreshedAt = createdAt.plusSeconds(900)

        // when
        val rotated = session.rotateRefreshToken(initialHash, replacementHash, refreshedAt)
        val replayed = session.rotateRefreshToken(initialHash, RefreshTokenHash("c".repeat(64)), refreshedAt.plusSeconds(1))

        // then
        assertThat(rotated).isTrue()
        assertThat(replayed).isFalse()
        assertThat(session.refreshTokenHash).isEqualTo(replacementHash)
        assertThat(session.lastRefreshedAt).isEqualTo(refreshedAt)
        assertThat(session.expiresAt).isEqualTo(expiresAt)
        assertThat(session.isActiveAt(refreshedAt)).isTrue()
    }

    @Test
    fun `만료된 세션은 갱신할 수 없다`() {
        // given
        val session = session()

        // when
        val rotated = session.rotateRefreshToken(initialHash, replacementHash, expiresAt)

        // then
        assertThat(rotated).isFalse()
        assertThat(session.refreshTokenHash).isEqualTo(initialHash)
        assertThat(session.lastRefreshedAt).isNull()
    }

    @Test
    fun `한 세션을 폐기해도 다른 기기 세션은 유지되고 폐기된 세션은 갱신할 수 없다`() {
        // given
        val session = session()
        val otherSession = AuthSession("account-01", replacementHash, createdAt, expiresAt)
        val revokedAt = createdAt.plusSeconds(60)

        // when
        session.revoke(revokedAt)
        session.revoke(revokedAt.plusSeconds(60))
        val rotated = session.rotateRefreshToken(initialHash, replacementHash, revokedAt)

        // then
        assertThat(session.revokedAt).isEqualTo(revokedAt)
        assertThat(session.isActiveAt(revokedAt)).isFalse()
        assertThat(rotated).isFalse()
        assertThat(otherSession.isActiveAt(revokedAt)).isTrue()
    }

    @ParameterizedTest
    @ValueSource(longs = [-1, 0])
    fun `생성 이후에 만료되지 않는 세션은 만들 수 없다`(seconds: Long) {
        // when & then
        assertThatThrownBy { AuthSession("account-01", initialHash, createdAt, createdAt.plusSeconds(seconds)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `갱신 시각 이전으로 돌아가거나 같은 토큰으로 교체할 수 없다`() {
        // given
        val session = session()
        val refreshedAt = createdAt.plusSeconds(900)
        session.rotateRefreshToken(initialHash, replacementHash, refreshedAt)

        // when & then
        assertThatThrownBy {
            session.rotateRefreshToken(replacementHash, initialHash, refreshedAt.minusSeconds(1))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            session.rotateRefreshToken(replacementHash, replacementHash, refreshedAt)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(session.refreshTokenHash).isEqualTo(replacementHash)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "raw-refresh-token", "ABCDEF", "abc123"])
    fun `해시 값에 원문 토큰이나 잘못된 다이제스트를 넣을 수 없다`(value: String) {
        // when & then
        assertThatThrownBy { RefreshTokenHash(value) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun session(): AuthSession = AuthSession("account-01", initialHash, createdAt, expiresAt)
}
