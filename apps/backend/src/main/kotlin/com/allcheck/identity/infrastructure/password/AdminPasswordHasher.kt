package com.allcheck.identity.infrastructure.password

import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AdminPasswordHasher {
    private val encoder = Pbkdf2PasswordEncoder("", 16, 600000, Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256)
    private val digestPattern = Regex("[0-9a-f]{96}")
    private val dummyDigest = encoder.encode(UUID.randomUUID().toString())

    fun encode(password: String): String {
        require(password.isNotBlank() && password.length <= 200) { "Invalid administrator password." }
        return PREFIX + encoder.encode(password)
    }

    fun matches(password: String, storedHash: String?): Boolean {
        val digest = storedHash?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { digestPattern.matches(it) }
        // 없는 계정과 지원하지 않는 저장 형식도 같은 비용의 비밀번호 검증을 수행한다.
        val matched = encoder.matches(password, digest ?: dummyDigest)
        return digest != null && matched
    }

    companion object {
        private const val PREFIX = "{pbkdf2-sha256-600000}"
    }
}
