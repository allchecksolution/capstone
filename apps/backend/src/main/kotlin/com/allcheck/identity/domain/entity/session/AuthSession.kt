package com.allcheck.identity.domain.entity.session

import com.allcheck.identity.domain.vo.session.RefreshTokenHash
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant

@Entity
@Table(
    name = "identity_auth_sessions",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_identity_auth_sessions_refresh_hash", columnNames = ["refresh_token_hash"]),
    ],
    indexes = [Index(name = "ix_identity_auth_sessions_account", columnList = "account_id")],
)
class AuthSession(
    @Column(name = "account_id", nullable = false, updatable = false)
    val accountId: Long,
    refreshTokenHash: RefreshTokenHash,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "expires_at", nullable = false, updatable = false)
    val expiresAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

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
        require(accountId > 0) { "Invalid session account ID." }
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
