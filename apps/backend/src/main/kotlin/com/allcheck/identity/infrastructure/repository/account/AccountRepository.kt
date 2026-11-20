package com.allcheck.identity.infrastructure.repository.account

import com.allcheck.identity.domain.entity.account.Account
import com.allcheck.identity.domain.vo.account.SocialLogin
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
                (account_id, user_type, login_id, password_hash, admin_role, enabled, created_at, version)
            VALUES (:accountId, 'ADMIN', :loginId, :passwordHash, :role, TRUE, :createdAt, 0)
            ON CONFLICT (login_id) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertAdministratorIfAbsent(
        @Param("accountId") accountId: String,
        @Param("loginId") loginId: String,
        @Param("passwordHash") passwordHash: String,
        @Param("role") role: String,
        @Param("createdAt") createdAt: Instant,
    ): Int

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
