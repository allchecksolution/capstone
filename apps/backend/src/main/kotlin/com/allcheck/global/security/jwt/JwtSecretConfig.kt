package com.allcheck.global.security.jwt

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@Configuration
class JwtSecretConfig {
    @Bean
    fun jwtSecretKey(@Value("\${jwt.secret:}") encodedSecret: String): SecretKey {
        require(encodedSecret.isNotBlank()) { "JWT_SECRET must be configured as a Base64-encoded secret." }
        val keyBytes = try {
            Base64.getDecoder().decode(encodedSecret)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("JWT_SECRET must be valid Base64.")
        }
        require(keyBytes.size >= 32) { "JWT_SECRET must decode to at least 32 bytes (256 bits) for HS256." }
        return SecretKeySpec(keyBytes, "HmacSHA256")
    }
}
