package com.allcheck.identity.infrastructure.repository

import com.allcheck.identity.domain.entity.Account
import com.allcheck.identity.domain.vo.SocialLogin
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface AccountRepository : JpaRepository<Account, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from Account account where account.id = :accountId")
    fun findByIdForUpdate(@Param("accountId") accountId: String): Account?

    fun findBySocialLogin(socialLogin: SocialLogin): Account?

    fun findByAdminCredentialLoginId(loginId: String): Account?

    @Modifying
    @Query(
        value = """
            INSERT INTO identity_accounts
                (account_id, user_type, social_provider, social_user_id, enabled, created_at, version)
            VALUES (:accountId, 'DRIVER', 'KAKAO', :socialUserId, TRUE, :createdAt, 0)
            ON CONFLICT (social_provider, social_user_id) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertKakaoIfAbsent(
        @Param("accountId") accountId: String,
        @Param("socialUserId") socialUserId: String,
        @Param("createdAt") createdAt: Instant,
    ): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from Account account where account.socialLogin = :socialLogin")
    fun findBySocialLoginForUpdate(@Param("socialLogin") socialLogin: SocialLogin): Account?
}
