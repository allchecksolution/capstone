package com.allcheck.identity.domain.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

@Entity
@Table(name = "identity_push_devices")
class PushDevice(
    @Id @Column(name = "installation_id", nullable = false, length = 64, updatable = false) val installationId: String,
    accountId: String,
    sessionId: String,
    platform: String,
    token: String,
    tokenHash: String,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant,
) {
    @Column(name = "account_id", nullable = false, length = 64)
    var accountId: String = accountId
        protected set

    @Column(name = "session_id", nullable = false, length = 64)
    var sessionId: String = sessionId
        protected set

    @Column(nullable = false, length = 10)
    var platform: String = platform
        protected set

    @Column(name = "push_token", columnDefinition = "text")
    var token: String? = token
        protected set

    @Column(name = "token_hash", length = 64)
    var tokenHash: String? = tokenHash
        protected set

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @Version
    @Column(nullable = false)
    var version: Long? = null
        protected set

    fun bind(accountId: String, sessionId: String, platform: String, token: String, hash: String, now: Instant) {
        if (revokedAt == null && this.accountId == accountId && this.sessionId == sessionId && this.platform == platform && this.token == token && tokenHash == hash) return
        this.accountId = accountId
        this.sessionId = sessionId
        this.platform = platform
        this.token = token
        tokenHash = hash
        revokedAt = null
        updatedAt = now
    }

    fun revoke(now: Instant) {
        if (revokedAt != null) return
        token = null
        tokenHash = null
        revokedAt = now
        updatedAt = now
    }
}
