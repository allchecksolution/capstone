package com.allcheck.identity.presentation.account

import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.application.account.AdminAccountService
import com.allcheck.identity.application.account.AdminAccountTransactions
import com.allcheck.identity.presentation.api.account.AdminAccountApi
import com.allcheck.identity.presentation.dto.request.account.ChangeAdminPasswordRequest
import com.allcheck.identity.presentation.dto.request.account.CreateAdminRequest
import com.allcheck.identity.presentation.dto.request.account.UpdateAdminRequest
import com.allcheck.identity.presentation.dto.response.account.AdminAccountListResponse
import com.allcheck.identity.presentation.dto.response.account.AdminAccountResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminAccountController(private val service: AdminAccountService, private val transactions: AdminAccountTransactions) : AdminAccountApi {
    override fun list(principal: AuthPrincipal, page: Int, size: Int): ResponseEntity<AdminAccountListResponse> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(transactions.list(principal.accountId, page, size))

    override fun create(principal: AuthPrincipal, request: CreateAdminRequest): ResponseEntity<AdminAccountResponse> = ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.create(principal.accountId, request.loginId, request.initialPassword))

    override fun update(principal: AuthPrincipal, accountId: Long, request: UpdateAdminRequest): ResponseEntity<AdminAccountResponse> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(principal.accountId, accountId, request.expectedVersion, request.loginId, request.newPassword))

    override fun deactivate(principal: AuthPrincipal, accountId: Long, expectedVersion: Long): ResponseEntity<Void> {
        transactions.deactivate(principal.accountId, accountId, expectedVersion)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    override fun changePassword(principal: AuthPrincipal, request: ChangeAdminPasswordRequest): ResponseEntity<Void> {
        service.changeOwnPassword(principal.accountId, request.currentPassword, request.newPassword)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }
}
