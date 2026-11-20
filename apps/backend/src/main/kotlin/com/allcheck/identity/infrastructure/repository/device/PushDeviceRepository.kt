package com.allcheck.identity.infrastructure.repository.device

import com.allcheck.identity.domain.entity.device.PushDevice
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface PushDeviceRepository : JpaRepository<PushDevice, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select device from PushDevice device where device.installationId = :id")
    fun findForUpdate(@Param("id") id: String): PushDevice?

    @Query("select account.id from Account account where account.enabled = true and account.userType = com.allcheck.identity.UserType.ADMIN")
    fun activeAdminIds(): List<String>

    @Query(
        """
        select device from PushDevice device join Account account on account.id = device.accountId
        join AuthSession session on session.id = device.sessionId and session.accountId = account.id
        where device.accountId in :accountIds and device.revokedAt is null and account.enabled = true
          and session.revokedAt is null and session.createdAt <= :now and session.expiresAt > :now
    """,
    )
    fun activeForAccounts(@Param("accountIds") accountIds: Collection<String>, @Param("now") now: Instant): List<PushDevice>

    @Modifying
    @Query("update PushDevice device set device.token = null, device.tokenHash = null, device.revokedAt = :now, device.updatedAt = :now, device.version = device.version + 1 where device.sessionId = :sessionId and device.revokedAt is null")
    fun revokeSession(@Param("sessionId") sessionId: String, @Param("now") now: Instant): Int

    @Modifying
    @Query("update PushDevice device set device.token = null, device.tokenHash = null, device.revokedAt = :now, device.updatedAt = :now, device.version = device.version + 1 where device.accountId = :accountId and device.revokedAt is null")
    fun revokeAccount(@Param("accountId") accountId: String, @Param("now") now: Instant): Int
}
