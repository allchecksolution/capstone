package com.allcheck.identity.infrastructure.repository

import com.allcheck.identity.domain.entity.AuthSession
import com.allcheck.identity.domain.vo.RefreshTokenHash
import org.springframework.data.jpa.repository.JpaRepository

interface AuthSessionRepository : JpaRepository<AuthSession, String> {
    fun findByRefreshTokenHash(refreshTokenHash: RefreshTokenHash): AuthSession?

    fun findAllByAccountId(accountId: String): List<AuthSession>
}
