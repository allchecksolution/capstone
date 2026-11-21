package com.allcheck.identity.infrastructure.repository.account

import com.allcheck.identity.UserType
import com.allcheck.identity.domain.entity.account.Account
import com.allcheck.identity.domain.vo.account.SocialLogin
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface AccountRepository : JpaRepository<Account, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from Account account where account.id = :accountId")
    fun findByIdForUpdate(@Param("accountId") accountId: Long): Account?

    fun findBySocialLogin(socialLogin: SocialLogin): Account?

    @Query("select account.id from Account account where account.id in :ids and account.enabled = true and account.userType = com.allcheck.identity.UserType.DRIVER")
    fun activeDriverIds(ids: Collection<Long>): Set<Long>

    fun existsByIdAndEnabledTrueAndUserType(id: Long, userType: UserType): Boolean

    fun findByAdminCredentialLoginId(loginId: String): Account?

    @Modifying
    @Query(
        value = """
            INSERT INTO identity_accounts
                (user_type, login_id, password_hash, admin_role, enabled, created_at, version)
            VALUES ('ADMIN', :loginId, :passwordHash, :role, TRUE, :createdAt, 0)
            ON CONFLICT (login_id) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertAdministratorIfAbsent(
        @Param("loginId") loginId: String,
        @Param("passwordHash") passwordHash: String,
        @Param("role") role: String,
        @Param("createdAt") createdAt: Instant,
    ): Int

    @Modifying
    @Query(
        value = """
            INSERT INTO identity_accounts
                (user_type, social_provider, social_user_id, enabled, created_at, version)
            VALUES ('DRIVER', 'KAKAO', :socialUserId, TRUE, :createdAt, 0)
            ON CONFLICT (social_provider, social_user_id) DO NOTHING
        """,
        nativeQuery = true,
    )
    fun insertKakaoIfAbsent(
        @Param("socialUserId") socialUserId: String,
        @Param("createdAt") createdAt: Instant,
    ): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from Account account where account.socialLogin = :socialLogin")
    fun findBySocialLoginForUpdate(@Param("socialLogin") socialLogin: SocialLogin): Account?
}
