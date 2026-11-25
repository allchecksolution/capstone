package com.allcheck.global.security.jwt

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.MalformedJwtException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.Date
import javax.crypto.SecretKey

/** JWT 발급·검증만 담당하며 HTTP 요청 인증이나 SecurityContext 등록은 수행하지 않는다. */
@Component
class JwtTokenProvider(
    private val secretKey: SecretKey,
    @Value("\${jwt.access-token-expiration}") private val accessTokenExpiration: Duration,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${jwt.issuer:allcheck}") private val issuer: String = "allcheck",
    @Value("\${jwt.audience:allcheck-api}") private val audience: String = "allcheck-api",
) {
    init {
        require(accessTokenExpiration >= Duration.ofSeconds(1)) { "JWT access token expiration must be at least 1 second." }
        require(issuer.isNotBlank()) { "JWT issuer must not be blank." }
        require(audience.isNotBlank()) { "JWT audience must not be blank." }
    }

    val lifetimeSeconds: Long
        get() = accessTokenExpiration.seconds

    private val parser = Jwts.parser()
        .verifyWith(secretKey)
        .sig().clear().add(Jwts.SIG.HS256).and()
        .requireIssuer(issuer)
        .requireAudience(audience)
        .require("token_use", "access")
        .clock { Date.from(clock.instant()) }
        .build()

    fun createAccessToken(accountId: Long, sessionId: Long): String {
        require(accountId > 0) { "JWT account ID must not be blank." }
        require(sessionId > 0) { "JWT session ID must not be blank." }
        val issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        return Jwts.builder()
            .subject(accountId.toString())
            .claim("sid", sessionId.toString())
            .issuer(issuer)
            .audience().add(audience).and()
            .claim("token_use", "access")
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(issuedAt.plusSeconds(lifetimeSeconds)))
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()
    }

    /** 서명·용도·발급자·대상·필수 식별자와 만료를 검증하며 실패 시 JJWT 예외를 전파한다. */
    fun parseClaims(token: String): Claims {
        val claims = parser.parseSignedClaims(token).payload
        val expiration = claims.expiration
        val issuedAt = claims.issuedAt
        if (expiration == null ||
            issuedAt == null ||
            !expiration.after(issuedAt) ||
            claims.subject.isNullOrBlank() ||
            claims.get("sid", String::class.java).isNullOrBlank()
        ) {
            throw MalformedJwtException("Access token claims are incomplete or invalid.")
        }
        return claims
    }
}
