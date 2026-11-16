package com.allcheck.identity.domain.entity

import com.allcheck.identity.domain.vo.RefreshTokenHash
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "identity_auth_sessions",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_identity_auth_sessions_refresh_hash", columnNames = ["refresh_token_hash"]),
    ],
    indexes = [Index(name = "ix_identity_auth_sessions_account", columnList = "account_id")],
)
class AuthSession(
    @Column(name = "account_id", nullable = false, length = 64, updatable = false)
    val accountId: String,
    refreshTokenHash: RefreshTokenHash,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "expires_at", nullable = false, updatable = false)
    val expiresAt: Instant,
) {
    @Id
    @Column(name = "session_id", nullable = false, length = 64, updatable = false)
    val id: String = UUID.randomUUID().toString()

    @Embedded
    var refreshTokenHash: RefreshTokenHash = refreshTokenHash
        protected set

    @Column(name = "last_refreshed_at")
    var lastRefreshedAt: Instant? = null
        protected set

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    init {
        require(accountId.isNotBlank() && accountId.length <= 64) { "Invalid session account ID." }
        require(expiresAt.isAfter(createdAt)) { "Session expiry must follow creation." }
    }

    fun isActiveAt(now: Instant): Boolean = revokedAt == null && !now.isBefore(createdAt) && now.isBefore(expiresAt)

    fun rotateRefreshToken(expected: RefreshTokenHash, replacement: RefreshTokenHash, now: Instant): Boolean {
        require(expected != replacement) { "Refresh token rotation must replace the token." }
        if (!isActiveAt(now) || refreshTokenHash != expected) return false
        require(!now.isBefore(lastRefreshedAt ?: createdAt)) { "Refresh time cannot move backwards." }
        refreshTokenHash = replacement
        lastRefreshedAt = now
        return true
    }

    fun revoke(now: Instant) {
        if (revokedAt != null) return
        require(!now.isBefore(lastRefreshedAt ?: createdAt)) { "Revocation cannot precede session activity." }
        revokedAt = now
    }
}
