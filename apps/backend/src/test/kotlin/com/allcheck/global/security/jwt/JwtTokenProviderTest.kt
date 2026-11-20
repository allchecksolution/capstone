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
        val subject = 1L

        // when
        val token = provider.createAccessToken(subject, 2L)
        val claims = provider.parseClaims(token)

        // then
        assertThat(token.split('.')).hasSize(3)
        assertThat(claims.keys).containsExactlyInAnyOrder("sub", "sid", "iss", "aud", "token_use", "iat", "exp")
        assertThat(claims.subject).isEqualTo(subject.toString())
        assertThat(claims.get("sid", String::class.java)).isEqualTo("2")
        assertThat(claims.issuer).isEqualTo("allcheck")
        assertThat(claims.audience).containsExactly("allcheck-api")
        assertThat(claims["token_use"]).isEqualTo("access")
        assertThat(claims.issuedAt).isEqualTo(Date.from(issuedAt))
        assertThat(claims.expiration).isEqualTo(Date.from(issuedAt.plus(expiration)))
    }

    @Test
    fun `만료 시간이 지난 토큰은 검증에 실패한다`() {
        // given
        val token = provider.createAccessToken(1L, 2L)
        val expiredClock = Clock.fixed(issuedAt.plus(expiration).plusSeconds(1), ZoneOffset.UTC)
        val expiredProvider = JwtTokenProvider(secretKey, expiration, expiredClock)

        // when & then
        assertThatThrownBy { expiredProvider.parseClaims(token) }
            .isInstanceOf(ExpiredJwtException::class.java)
    }

    @Test
    fun `Payload를 변조한 토큰은 서명 검증에 실패한다`() {
        // given
        val parts = provider.createAccessToken(1L, 2L).split('.')
        val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
            .replace("\"sub\":\"1\"", "\"sub\":\"99\"")
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
        val token = JwtTokenProvider(otherKey, expiration, clock).createAccessToken(1L, 2L)

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
        val token = Jwts.builder()
            .subject("test-subject")
            .issuer("allcheck")
            .audience().add("allcheck-api").and()
            .claim("sid", "test-session")
            .claim("token_use", "access")
            .issuedAt(Date.from(issuedAt))
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()

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
    @ValueSource(strings = ["sub", "sid", "iss", "aud", "iat", "exp", "token_use"])
    fun `필수 인증 Claims가 누락된 서명 토큰도 거부한다`(field: String) {
        // given
        val claims = HashMap<String, Any>(provider.parseClaims(provider.createAccessToken(1L, 2L)))
        claims.remove(field)
        val token = Jwts.builder().claims(claims).signWith(secretKey, Jwts.SIG.HS256).compact()

        // when & then
        assertThatThrownBy { provider.parseClaims(token) }.isInstanceOf(JwtException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["iss", "aud", "token_use"])
    fun `다른 발급자 대상 또는 용도의 토큰은 거부한다`(field: String) {
        // given
        val claims = HashMap<String, Any>(provider.parseClaims(provider.createAccessToken(1L, 2L)))
        claims[field] = if (field == "aud") listOf("other-service") else "other-service"
        val token = Jwts.builder().claims(claims).signWith(secretKey, Jwts.SIG.HS256).compact()

        // when & then
        assertThatThrownBy { provider.parseClaims(token) }.isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `양수가 아닌 계정 또는 세션 식별자로 토큰을 만들 수 없다`() {
        // when & then
        assertThatThrownBy { provider.createAccessToken(0L, 2L) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { provider.createAccessToken(1L, 0L) }.isInstanceOf(IllegalArgumentException::class.java)
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
