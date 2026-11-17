package com.allcheck.identity.presentation.api

import com.allcheck.global.exception.ErrorResponse
import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.presentation.dto.request.PushDeviceRequest
import com.allcheck.identity.presentation.dto.response.PushDeviceResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping

@Tag(name = "Push Devices")
@RequestMapping("/api/v1/push-devices")
@SecurityRequirement(name = "bearerAuth")
@ApiResponses(
    ApiResponse(responseCode = "400", description = "입력 오류", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "401", description = "인증 필요", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "403", description = "다른 계정의 기기 변경 불가", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
    ApiResponse(responseCode = "409", description = "활성 기기·토큰 소유권 충돌", content = [Content(schema = Schema(implementation = ErrorResponse::class))]),
)
interface PushDeviceApi {
    @PutMapping("/{installationId}")
    @Operation(summary = "본인 관리자·기사 앱의 푸시 기기 등록 또는 갱신", operationId = "registerPushDevice")
    fun register(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal,
        @NotBlank @Size(min = 1, max = 64) @PathVariable installationId: String,
        @Valid @RequestBody request: PushDeviceRequest,
    ): ResponseEntity<PushDeviceResponse>

    @DeleteMapping("/{installationId}")
    @Operation(summary = "본인 푸시 기기 연결 해제", operationId = "unregisterPushDevice")
    @ApiResponse(responseCode = "204", description = "기기 연결 해제 완료", content = [Content()])
    fun remove(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal,
        @NotBlank @Size(min = 1, max = 64) @PathVariable installationId: String,
    ): ResponseEntity<Void>
}
