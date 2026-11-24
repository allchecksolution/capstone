package com.allcheck.identity.presentation.dto.response.account

import com.allcheck.identity.AdminRole
import com.allcheck.identity.domain.entity.account.Account
import io.swagger.v3.oas.annotations.media.Schema

data class AdminAccountResponse(
    @field:Schema(description = "관리자 계정 식별자") val accountId: Long,
    @field:Schema(description = "로그인 아이디") val loginId: String,
    @field:Schema(description = "역할 구분. 업무 권한은 동일") val role: AdminRole,
    @field:Schema(description = "최초 관리자 여부") val isBootstrapAdmin: Boolean,
    @field:Schema(description = "로그인 가능 여부") val enabled: Boolean,
    @field:Schema(description = "수정과 비활성화 시 전달할 현재 버전") val version: Long,
) {
    companion object {
        fun from(account: Account): AdminAccountResponse {
            val credential = checkNotNull(account.adminCredential)
            return AdminAccountResponse(checkNotNull(account.id), credential.loginId, credential.role, account.isBootstrapAdmin, account.enabled, checkNotNull(account.version))
        }
    }
}

data class AdminAccountListResponse(
    @field:Schema(description = "관리자 목록") val items: List<AdminAccountResponse>,
    @field:Schema(description = "현재 페이지. 1부터 시작") val page: Int,
    @field:Schema(description = "페이지 크기") val size: Int,
    @field:Schema(description = "전체 관리자 수") val totalElements: Long,
)
