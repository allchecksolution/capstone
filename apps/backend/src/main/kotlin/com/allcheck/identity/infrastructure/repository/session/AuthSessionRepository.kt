package com.allcheck.identity.infrastructure.repository.session

import com.allcheck.identity.domain.entity.account.Account
import com.allcheck.identity.domain.entity.session.AuthSession
import com.allcheck.identity.domain.vo.session.RefreshTokenHash
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface AuthSessionRepository : JpaRepository<AuthSession, String> {
    fun findByRefreshTokenHash(refreshTokenHash: RefreshTokenHash): AuthSession?

    @Query("select session.accountId from AuthSession session where session.refreshTokenHash = :hash")
    fun findAccountIdByRefreshTokenHash(@Param("hash") hash: RefreshTokenHash): String?

    @Query(
        """
        select account as account, session as session
        from AuthSession session join Account account on account.id = session.accountId
        where session.refreshTokenHash = :hash
        """,
    )
    fun findRefreshAuthenticationState(@Param("hash") hash: RefreshTokenHash): AuthenticationState?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AuthSession session where session.refreshTokenHash = :hash")
    fun findByRefreshTokenHashForUpdate(@Param("hash") hash: RefreshTokenHash): AuthSession?

    fun findAllByAccountId(accountId: String): List<AuthSession>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AuthSession session where session.accountId = :accountId order by session.id")
    fun findAllByAccountIdForUpdate(@Param("accountId") accountId: String): List<AuthSession>

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
