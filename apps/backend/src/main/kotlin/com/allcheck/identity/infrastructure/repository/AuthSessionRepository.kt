package com.allcheck.identity.infrastructure.repository

import com.allcheck.identity.domain.entity.Account
import com.allcheck.identity.domain.entity.AuthSession
import com.allcheck.identity.domain.vo.RefreshTokenHash
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AuthSessionRepository : JpaRepository<AuthSession, String> {
    fun findByRefreshTokenHash(refreshTokenHash: RefreshTokenHash): AuthSession?

    fun findAllByAccountId(accountId: String): List<AuthSession>

    @Query(
        """
        select account as account, session as session
        from AuthSession session join Account account on account.id = session.accountId
        where account.id = :accountId and session.id = :sessionId
        """,
    )
    fun findAuthenticationState(@Param("accountId") accountId: String, @Param("sessionId") sessionId: String): AuthenticationState?
}

/** 계정과 세션을 한 쿼리의 동일한 DB 스냅샷에서 조회한다. 모듈 내부에서만 사용한다. */
interface AuthenticationState {
    val account: Account
    val session: AuthSession
}
