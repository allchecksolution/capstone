package com.allcheck.global.security.jwt

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.MalformedJwtException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.Date
import javax.crypto.SecretKey

/** JWT 발급·검증만 담당하며 HTTP 요청 인증이나 SecurityContext 등록은 수행하지 않는다. */
@Component
class JwtTokenProvider(
    private val secretKey: SecretKey,
    @Value("\${jwt.access-token-expiration}") private val accessTokenExpiration: Duration,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(accessTokenExpiration >= Duration.ofSeconds(1)) { "JWT access token expiration must be at least 1 second." }
    }

    private val parser = Jwts.parser()
        .verifyWith(secretKey)
        .sig().clear().add(Jwts.SIG.HS256).and()
        .clock { Date.from(clock.instant()) }
        .build()

    /* TODO: 인증 도메인 확정 후 Access Token 생성 파라미터를
        사용자 식별자(userId), 사용자 유형(userType), 권한(roles) 기준으로 재설계하고,
        각 값을 JWT Claims에 어떻게 반영할지 결정한다.
     */
    fun createAccessToken(subject: String): String {
        require(subject.isNotBlank()) { "JWT subject must not be blank." }
        val issuedAt = clock.instant()
        return Jwts.builder()
            .subject(subject)
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(issuedAt.plus(accessTokenExpiration)))
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()
    }

    /** 서명·알고리즘·만료를 검증한 Claims를 반환하며 실패 시 JJWT 예외를 전파한다. */
    fun parseClaims(token: String): Claims {
        val claims = parser.parseSignedClaims(token).payload
        if (claims.expiration == null) {
            throw MalformedJwtException("JWT expiration is required.")
        }
        return claims
    }

    // TODO: issuer, audience, 토큰 용도 등 추가 Claims 및 검증 정책을 확정한다.
    // TODO: Resource Server 채택 시 JwtEncoder/JwtDecoder 기반 통합을 검토한다.
}
