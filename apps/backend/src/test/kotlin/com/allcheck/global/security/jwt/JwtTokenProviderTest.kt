package com.allcheck.global.security.jwt

import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.MalformedJwtException
import io.jsonwebtoken.UnsupportedJwtException
import io.jsonwebtoken.security.SignatureException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Date
import javax.crypto.spec.SecretKeySpec

class JwtTokenProviderTest {
    private val secretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256")
    private val issuedAt = Instant.parse("2026-11-10T00:00:00Z")
    private val clock = Clock.fixed(issuedAt, ZoneOffset.UTC)
    private val expiration = Duration.ofMinutes(15)
    private val provider = JwtTokenProvider(secretKey, expiration, clock)

    @Test
    fun `Access Token은 HS256 서명 검증을 통과하고 최소 Claims와 설정된 만료 시간을 갖는다`() {
        // given
        val subject = "test-subject"

        // when
        val token = provider.createAccessToken(subject)
        val claims = provider.parseClaims(token)

        // then
        assertThat(token.split('.')).hasSize(3)
        assertThat(claims.keys).containsExactlyInAnyOrder("sub", "iat", "exp")
        assertThat(claims.subject).isEqualTo(subject)
        assertThat(claims.issuedAt).isEqualTo(Date.from(issuedAt))
        assertThat(claims.expiration).isEqualTo(Date.from(issuedAt.plus(expiration)))
    }

    @Test
    fun `만료 시간이 지난 토큰은 검증에 실패한다`() {
        // given
        val token = provider.createAccessToken("test-subject")
        val expiredClock = Clock.fixed(issuedAt.plus(expiration).plusSeconds(1), ZoneOffset.UTC)
        val expiredProvider = JwtTokenProvider(secretKey, expiration, expiredClock)

        // when & then
        assertThatThrownBy { expiredProvider.parseClaims(token) }
            .isInstanceOf(ExpiredJwtException::class.java)
    }

    @Test
    fun `Payload를 변조한 토큰은 서명 검증에 실패한다`() {
        // given
        val parts = provider.createAccessToken("test-subject").split('.')
        val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
            .replace("test-subject", "other-subject")
        val tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
        val token = "${parts[0]}.$tamperedPayload.${parts[2]}"

        // when & then
        assertThatThrownBy { provider.parseClaims(token) }
            .isInstanceOf(SignatureException::class.java)
    }

    @Test
    fun `다른 키로 서명한 토큰은 검증에 실패한다`() {
        // given
        val otherKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "HmacSHA256")
        val token = JwtTokenProvider(otherKey, expiration, clock).createAccessToken("test-subject")

        // when & then
        assertThatThrownBy { provider.parseClaims(token) }
            .isInstanceOf(SignatureException::class.java)
    }

    @Test
    fun `서명되지 않은 토큰은 허용하지 않는다`() {
        // given
        val token = Jwts.builder()
            .subject("test-subject")
            .expiration(Date.from(issuedAt.plus(expiration)))
            .compact()

        // when & then
        assertThatThrownBy { provider.parseClaims(token) }
            .isInstanceOf(UnsupportedJwtException::class.java)
    }

    @Test
    fun `키가 충분히 길어도 HS256 이외의 알고리즘은 허용하지 않는다`() {
        // given
        val keyBytes = ByteArray(64) { it.toByte() }
        val hs512Key = SecretKeySpec(keyBytes, "HmacSHA512")
        val token = Jwts.builder()
            .subject("test-subject")
            .expiration(Date.from(issuedAt.plus(expiration)))
            .signWith(hs512Key, Jwts.SIG.HS512)
            .compact()
        val hs256Provider = JwtTokenProvider(SecretKeySpec(keyBytes, "HmacSHA256"), expiration, clock)

        // when & then
        assertThatThrownBy { hs256Provider.parseClaims(token) }
            .isInstanceOf(SignatureException::class.java)
    }

    @Test
    fun `만료 시간이 없는 토큰은 허용하지 않는다`() {
        // given
        val token = Jwts.builder().subject("test-subject").signWith(secretKey, Jwts.SIG.HS256).compact()

        // when & then
        assertThatThrownBy { provider.parseClaims(token) }
            .isInstanceOf(MalformedJwtException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["not-a-jwt", "a.b.c"])
    fun `잘못된 형식의 토큰은 허용하지 않는다`(token: String) {
        // when & then
        assertThatThrownBy { provider.parseClaims(token) }
            .isInstanceOf(JwtException::class.java)
    }

    @ParameterizedTest
    @ValueSource(longs = [-1, 0, 500])
    fun `1초 미만의 만료 시간은 설정 오류로 처리한다`(milliseconds: Long) {
        // when & then
        assertThatThrownBy { JwtTokenProvider(secretKey, Duration.ofMillis(milliseconds), clock) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("expiration")
    }
}
