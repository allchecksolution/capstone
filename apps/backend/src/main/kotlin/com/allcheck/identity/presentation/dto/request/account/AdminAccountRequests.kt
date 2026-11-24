package com.allcheck.identity.presentation.dto.request.account

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

abstract class StrictAdminRequest {
    @JsonAnySetter
    @Suppress("UNUSED_PARAMETER")
    fun rejectUnknownField(field: String, value: Any?): Nothing = throw IllegalArgumentException("Unknown administrator fields are not supported.")
}

class CreateAdminRequest(
    @field:NotBlank @field:Size(max = 100) @field:Schema(description = "새 관리자 로그인 아이디") val loginId: String,
    @field:NotBlank @field:Size(max = 200) @field:Schema(description = "초기 비밀번호. 첫 로그인 변경 강제 없음", accessMode = Schema.AccessMode.WRITE_ONLY) val initialPassword: String,
) : StrictAdminRequest() {
    @get:JsonIgnore
    @get:AssertTrue(message = "로그인 아이디 형식이 올바르지 않습니다.")
    val isValidLoginId: Boolean get() = '\u0000' !in loginId
}

class UpdateAdminRequest(
    @field:Min(0) @field:Schema(description = "목록에서 조회한 현재 버전") val expectedVersion: Long,
    @field:Size(min = 1, max = 100) @field:Schema(description = "변경할 아이디. 생략하면 유지") val loginId: String? = null,
    @field:Size(min = 1, max = 200) @field:Schema(description = "재설정할 비밀번호. 생략하면 유지", accessMode = Schema.AccessMode.WRITE_ONLY) val newPassword: String? = null,
) : StrictAdminRequest() {
    @get:JsonIgnore
    @get:AssertTrue(message = "유효한 변경 항목이 필요합니다.")
    val isValidChange: Boolean get() = (loginId != null || newPassword != null) && (loginId == null || loginId.isNotBlank() && '\u0000' !in loginId) && (newPassword == null || newPassword.isNotBlank())
}

class ChangeAdminPasswordRequest(
    @field:NotBlank @field:Size(max = 200) @field:Schema(description = "현재 비밀번호", accessMode = Schema.AccessMode.WRITE_ONLY) val currentPassword: String,
    @field:NotBlank @field:Size(max = 200) @field:Schema(description = "새 비밀번호", accessMode = Schema.AccessMode.WRITE_ONLY) val newPassword: String,
) : StrictAdminRequest()
