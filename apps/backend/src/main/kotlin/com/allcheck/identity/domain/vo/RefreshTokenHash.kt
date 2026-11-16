package com.allcheck.identity.domain.vo

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
class RefreshTokenHash(
    @Column(name = "refresh_token_hash", nullable = false, length = 64)
    val value: String,
) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) { "Refresh token hash must be a SHA-256 hex digest." }
    }

    override fun equals(other: Any?): Boolean = other is RefreshTokenHash && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "RefreshTokenHash([redacted])"
}
