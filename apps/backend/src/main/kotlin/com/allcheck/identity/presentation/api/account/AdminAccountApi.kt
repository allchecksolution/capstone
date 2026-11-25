package com.allcheck.identity.presentation.api.account

import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.presentation.dto.request.account.ChangeAdminPasswordRequest
import com.allcheck.identity.presentation.dto.request.account.CreateAdminRequest
import com.allcheck.identity.presentation.dto.request.account.UpdateAdminRequest
import com.allcheck.identity.presentation.dto.response.account.AdminAccountListResponse
import com.allcheck.identity.presentation.dto.response.account.AdminAccountResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

@Tag(name = "Administrator accounts")
@RequestMapping("/api/v1/admin-accounts")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
@ApiResponses(
    ApiResponse(responseCode = "400", description = "입력 형식 오류"),
    ApiResponse(responseCode = "401", description = "인증 필요"),
    ApiResponse(responseCode = "403", description = "최초 관리자 권한 필요 또는 보호된 계정"),
    ApiResponse(responseCode = "404", description = "계정 없음"),
    ApiResponse(responseCode = "409", description = "아이디 중복 또는 현재 버전 충돌"),
)
interface AdminAccountApi {
    @GetMapping
    @Operation(summary = "최초 관리자의 관리자 목록 조회")
    fun list(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal,
        @Min(1) @RequestParam(defaultValue = "1") page: Int,
        @Min(1) @Max(100) @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<AdminAccountListResponse>

    @PostMapping
    @Operation(summary = "최초 관리자의 추가 관리자 생성")
    @ApiResponse(responseCode = "201", description = "추가 계정 생성. 세션 발급 없음")
    fun create(@Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal, @Valid @RequestBody request: CreateAdminRequest): ResponseEntity<AdminAccountResponse>

    @PutMapping("/{accountId}")
    @Operation(summary = "추가 관리자 아이디 변경 및 비밀번호 재설정", description = "최초 관리자만 사용. 변경 시 대상의 모든 세션 폐기.")
    fun update(@Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal, @Min(1) @PathVariable accountId: Long, @Valid @RequestBody request: UpdateAdminRequest): ResponseEntity<AdminAccountResponse>

    @DeleteMapping("/{accountId}")
    @Operation(summary = "추가 관리자 비활성화", description = "최초 관리자 본인 탈퇴 불가. 업무 이력과 계정을 보존하고 모든 세션 폐기.")
    @ApiResponse(responseCode = "204", description = "비활성화 완료")
    fun deactivate(@Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal, @Min(1) @PathVariable accountId: Long, @Min(0) @RequestParam expectedVersion: Long): ResponseEntity<Void>

    @PutMapping("/me/password")
    @Operation(summary = "관리자 본인 비밀번호 변경", description = "모든 관리자 사용 가능. 현재 비밀번호 확인 후 모든 세션 폐기. 첫 로그인 변경 강제 없음.")
    @ApiResponse(responseCode = "204", description = "변경 완료. 다시 로그인 필요")
    fun changePassword(@Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal, @Valid @RequestBody request: ChangeAdminPasswordRequest): ResponseEntity<Void>
}
