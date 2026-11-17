package com.allcheck.identity.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.PushDeviceOperations
import com.allcheck.identity.PushTarget
import com.allcheck.identity.domain.entity.PushDevice
import com.allcheck.identity.exception.IdentityErrorCode
import com.allcheck.identity.infrastructure.repository.AccountRepository
import com.allcheck.identity.infrastructure.repository.AuthSessionRepository
import com.allcheck.identity.infrastructure.repository.PushDeviceRepository
import com.allcheck.identity.infrastructure.token.RefreshTokenProvider
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
@Transactional(readOnly = true)
class PushDeviceService(
    private val devices: PushDeviceRepository,
    private val accounts: AccountRepository,
    private val sessions: AuthSessionRepository,
    private val digests: RefreshTokenProvider,
    private val clock: Clock = Clock.systemUTC(),
) : PushDeviceOperations {
    override fun activeAdminAccountIds(): List<String> = devices.activeAdminIds()

    override fun activeDevices(accountIds: Collection<String>): List<PushTarget> = if (accountIds.isEmpty()) emptyList() else devices.activeForAccounts(accountIds, clock.instant()).map { it.target() }

    override fun currentDevice(installationId: String, accountId: String): PushTarget? = devices.activeForAccounts(listOf(accountId), clock.instant()).firstOrNull { it.installationId == installationId }?.target()

    @Transactional
    fun register(accountId: String, sessionId: String, installationId: String, platform: String, token: String): Instant {
        val account = accounts.findByIdForUpdate(accountId) ?: throw BusinessException(IdentityErrorCode.ACCOUNT_NOT_FOUND)
        val session = sessions.findById(sessionId).orElseThrow { BusinessException(IdentityErrorCode.INVALID_AUTH_SESSION) }
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        if (!account.enabled || session.accountId != accountId || !session.isActiveAt(now)) throw BusinessException(IdentityErrorCode.INVALID_AUTH_SESSION)
        val device = devices.findForUpdate(installationId)
        if (device != null && device.revokedAt == null && device.accountId != accountId) throw BusinessException(IdentityErrorCode.PUSH_DEVICE_CONFLICT)
        val hash = digests.hash(token).value
        try {
            if (device == null) {
                devices.save(PushDevice(installationId, accountId, sessionId, platform, token, hash, now))
            } else {
                device.bind(accountId, sessionId, platform, token, hash, now)
            }
            devices.flush()
        } catch (ex: DataIntegrityViolationException) {
            val constraint = generateSequence<Throwable>(ex) { it.cause }.filterIsInstance<ConstraintViolationException>().firstOrNull()?.constraintName
            if (constraint in setOf("identity_push_devices_pkey", "uq_identity_push_devices_active_token")) throw BusinessException(IdentityErrorCode.PUSH_DEVICE_CONFLICT)
            throw ex
        }
        return device?.updatedAt ?: now
    }

    @Transactional
    fun remove(accountId: String, installationId: String) {
        accounts.findByIdForUpdate(accountId) ?: throw BusinessException(IdentityErrorCode.ACCOUNT_NOT_FOUND)
        val device = devices.findForUpdate(installationId) ?: return
        if (device.accountId != accountId) throw BusinessException(IdentityErrorCode.PUSH_DEVICE_FORBIDDEN)
        device.revoke(clock.instant())
    }

    @Transactional
    override fun invalidate(target: PushTarget) {
        accounts.findByIdForUpdate(target.accountId) ?: return
        val device = devices.findForUpdate(target.installationId) ?: return
        if (device.accountId == target.accountId && device.tokenHash == target.tokenHash) device.revoke(clock.instant())
    }

    private fun PushDevice.target(): PushTarget = PushTarget(accountId, installationId, checkNotNull(token), checkNotNull(tokenHash))
}
