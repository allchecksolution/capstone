package com.allcheck.identity.application.account

import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.AdminRole
import com.allcheck.identity.UserType
import com.allcheck.identity.domain.entity.account.Account
import com.allcheck.identity.domain.entity.account.AdminHistory
import com.allcheck.identity.domain.vo.account.AdminCredential
import com.allcheck.identity.exception.IdentityErrorCode
import com.allcheck.identity.infrastructure.repository.account.AccountRepository
import com.allcheck.identity.infrastructure.repository.account.AdminHistoryRepository
import com.allcheck.identity.infrastructure.repository.device.PushDeviceRepository
import com.allcheck.identity.infrastructure.repository.session.AuthSessionRepository
import com.allcheck.identity.presentation.dto.response.account.AdminAccountListResponse
import com.allcheck.identity.presentation.dto.response.account.AdminAccountResponse
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
@Transactional
class AdminAccountTransactions(
    private val accounts: AccountRepository,
    private val history: AdminHistoryRepository,
    private val sessions: AuthSessionRepository,
    private val devices: PushDeviceRepository,
    private val jdbc: JdbcTemplate,
) {
    fun bootstrap(loginId: String, hash: String, role: AdminRole): Account {
        lockBootstrap()
        if (accounts.existsByIsBootstrapAdminTrue()) fail(IdentityErrorCode.BOOTSTRAP_ADMIN_ALREADY_EXISTS)
        ensureUnique(loginId)
        val account = Account.administrator(AdminCredential(loginId, hash, role), Instant.now())
        account.designateBootstrapAdministrator()
        accounts.saveAndFlush(account)
        audit(null, account, "BOOTSTRAP_CREATED")
        return account
    }

    fun adopt(loginId: String, verifiedHash: String): Account {
        lockBootstrap()
        if (accounts.existsByIsBootstrapAdminTrue()) fail(IdentityErrorCode.BOOTSTRAP_ADMIN_ALREADY_EXISTS)
        val found = accounts.findByAdminCredentialLoginId(loginId) ?: fail(IdentityErrorCode.ACCOUNT_NOT_FOUND)
        val account = lock(checkNotNull(found.id))
        if (!account.enabled) fail(IdentityErrorCode.ACCOUNT_DISABLED)
        if (account.adminCredential?.passwordHash != verifiedHash) fail(IdentityErrorCode.INVALID_CREDENTIALS)
        account.designateBootstrapAdministrator()
        audit(null, account, "BOOTSTRAP_ADOPTED")
        return account
    }

    fun recover(loginId: String, hash: String): Account {
        val found = accounts.findByIsBootstrapAdminTrue() ?: fail(IdentityErrorCode.ACCOUNT_NOT_FOUND)
        val account = lock(checkNotNull(found.id))
        if (account.adminCredential?.loginId != loginId) fail(IdentityErrorCode.ACCOUNT_NOT_FOUND)
        account.changeAdministratorCredential(loginId, hash)
        revoke(account)
        audit(null, account, "BOOTSTRAP_PASSWORD_RECOVERED")
        return account
    }

    fun create(actorId: Long, loginId: String, hash: String): AdminAccountResponse {
        requireBootstrap(lock(actorId))
        ensureUnique(loginId)
        val account = accounts.saveAndFlush(Account.administrator(AdminCredential(loginId, hash, AdminRole.SUB), Instant.now()))
        audit(actorId, account, "CREATED")
        return AdminAccountResponse.from(account)
    }

    fun update(actorId: Long, targetId: Long, version: Long, loginId: String?, hash: String?): AdminAccountResponse {
        val locked = listOf(actorId, targetId).distinct().sorted().associateWith(::lock)
        requireBootstrap(locked.getValue(actorId))
        val account = locked.getValue(targetId)
        if (account.isBootstrapAdmin) fail(IdentityErrorCode.BOOTSTRAP_ADMIN_PROTECTED)
        if (!account.enabled) fail(IdentityErrorCode.ACCOUNT_DISABLED)
        checkVersion(account, version)
        val credential = checkNotNull(account.adminCredential)
        val nextId = loginId ?: credential.loginId
        ensureUnique(nextId, targetId)
        account.changeAdministratorCredential(nextId, hash ?: credential.passwordHash)
        revoke(account)
        audit(actorId, account, if (hash == null) "LOGIN_ID_CHANGED" else "PASSWORD_RESET")
        accounts.flush()
        return AdminAccountResponse.from(account)
    }

    fun deactivate(actorId: Long, targetId: Long, version: Long) {
        val locked = listOf(actorId, targetId).distinct().sorted().associateWith(::lock)
        requireBootstrap(locked.getValue(actorId))
        val account = locked.getValue(targetId)
        if (account.isBootstrapAdmin) fail(IdentityErrorCode.BOOTSTRAP_ADMIN_PROTECTED)
        checkVersion(account, version)
        if (!account.enabled) return
        account.disable(Instant.now())
        revoke(account)
        audit(actorId, account, "DEACTIVATED")
    }

    fun changeOwnPassword(actorId: Long, verifiedHash: String, newHash: String) {
        val account = lock(actorId)
        if (!account.enabled) fail(IdentityErrorCode.ACCOUNT_DISABLED)
        val credential = checkNotNull(account.adminCredential)
        if (credential.passwordHash != verifiedHash) fail(IdentityErrorCode.INVALID_CREDENTIALS)
        account.changeAdministratorCredential(credential.loginId, newHash)
        revoke(account)
        audit(actorId, account, "OWN_PASSWORD_CHANGED")
    }

    @Transactional(readOnly = true)
    fun list(actorId: Long, page: Int, size: Int): AdminAccountListResponse {
        requireBootstrap(accounts.findById(actorId).orElseThrow { BusinessException(IdentityErrorCode.ACCOUNT_NOT_FOUND) })
        val result = accounts.findByUserType(UserType.ADMIN, PageRequest.of(page - 1, size, Sort.by("id")))
        return AdminAccountListResponse(result.content.map(AdminAccountResponse::from), page, size, result.totalElements)
    }

    private fun lock(id: Long): Account {
        val account = accounts.findByIdForUpdate(id) ?: fail(IdentityErrorCode.ACCOUNT_NOT_FOUND)
        if (account.userType != UserType.ADMIN) fail(IdentityErrorCode.ADMIN_ACCOUNT_REQUIRED)
        return account
    }

    private fun requireBootstrap(account: Account) {
        if (!account.enabled || !account.isBootstrapAdmin) fail(IdentityErrorCode.BOOTSTRAP_ADMIN_REQUIRED)
    }

    private fun lockBootstrap() {
        jdbc.execute("SELECT pg_advisory_xact_lock(739421, 1)")
    }

    private fun ensureUnique(loginId: String, ownId: Long? = null) {
        val existing = accounts.findByAdminCredentialLoginId(loginId)
        if (existing != null && existing.id != ownId) fail(IdentityErrorCode.ADMIN_LOGIN_ID_ALREADY_EXISTS)
    }

    private fun checkVersion(account: Account, expected: Long) {
        if (account.version != expected) fail(IdentityErrorCode.ADMIN_ACCOUNT_VERSION_CONFLICT)
    }

    private fun revoke(account: Account) {
        val now = Instant.now()
        val id = checkNotNull(account.id)
        sessions.findAllByAccountIdForUpdate(id).forEach { it.revoke(now) }
        devices.revokeAccount(id, now)
    }

    private fun audit(actor: Long?, account: Account, action: String) {
        history.save(AdminHistory(actor, checkNotNull(account.id), action, Instant.now()))
    }

    private fun fail(code: IdentityErrorCode): Nothing = throw BusinessException(code)
}
