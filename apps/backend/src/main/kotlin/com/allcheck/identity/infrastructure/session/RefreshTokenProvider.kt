package com.allcheck.identity.infrastructure.session

import com.allcheck.identity.domain.vo.session.RefreshTokenHash
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

class RefreshTokenProvider(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun generate(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): RefreshTokenHash {
        require(token.isNotBlank()) { "Refresh token must not be blank." }
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return RefreshTokenHash(HexFormat.of().formatHex(digest))
    }
}
