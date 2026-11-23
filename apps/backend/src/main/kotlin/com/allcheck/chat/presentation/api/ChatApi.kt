package com.allcheck.chat.presentation.api

import com.allcheck.chat.presentation.dto.request.MarkChatRead
import com.allcheck.chat.presentation.dto.request.SendChatMessage
import com.allcheck.chat.presentation.dto.response.ChatMessagePageResponse
import com.allcheck.chat.presentation.dto.response.ChatMessageResponse
import com.allcheck.chat.presentation.dto.response.ChatReadResponse
import com.allcheck.chat.presentation.dto.response.ChatRoomPageResponse
import com.allcheck.global.security.principal.AuthPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

@RequestMapping("/api/v1", produces = ["application/json"])
@Tag(name = "Chat", description = "기사별 관리자–기사 1:1 채팅")
@SecurityRequirement(name = "bearerAuth")
internal interface ChatApi {

    @GetMapping("/chat-rooms")
    @Operation(operationId = "listChatRooms", summary = "ADMIN: 기사별 대화방 목록", description = "기사명 부분 검색과 최신 메시지 순 정렬. 대화가 시작된 기사만 포함한다.")
    fun rooms(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal?,
        @RequestParam(required = false) @Size(min = 1, max = 100) q: String?,
        @RequestParam(defaultValue = "1") @Min(1) @Max(1_000_000) page: Int,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): ResponseEntity<ChatRoomPageResponse>

    @GetMapping("/chat-rooms/{driverId}/messages")
    @Operation(operationId = "listChatMessages", summary = "ADMIN: 기사와의 대화 내역", description = "기본 최신순, before로 이전 페이지를 조회한다. after는 재접속 시 신규 메시지를 오래된 순으로 조회한다.")
    fun messages(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal?,
        @PathVariable @Positive driverId: Long,
        @RequestParam(required = false) @Size(max = 32) before: String?,
        @RequestParam(required = false) @Size(max = 32) after: String?,
        @RequestParam(defaultValue = "30") @Min(1) @Max(100) limit: Int,
    ): ResponseEntity<ChatMessagePageResponse>

    @PostMapping("/chat-rooms/{driverId}/messages", consumes = ["application/json"])
    @Operation(operationId = "sendChatMessage", summary = "ADMIN: 기사에게 메시지 전송", description = "동일 기사·계정·키에 같은 본문으로 재전송하면 처음 저장한 메시지를 반환한다.")
    @ApiResponse(responseCode = "201", description = "저장 완료. 상대의 수신 또는 읽음은 별도다.")
    @ApiResponse(responseCode = "409", description = "같은 키를 다른 본문으로 사용했다.")
    fun send(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal?,
        @PathVariable @Positive driverId: Long,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String,
        @Valid @RequestBody body: SendChatMessage,
    ): ResponseEntity<ChatMessageResponse>

    @PutMapping("/chat-rooms/{driverId}/read", consumes = ["application/json"])
    @Operation(operationId = "markChatRead", summary = "ADMIN: 관리자 측 공유 읽음 위치 전진")
    fun markRead(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal?,
        @PathVariable @Positive driverId: Long,
        @Valid @RequestBody body: MarkChatRead,
    ): ResponseEntity<ChatReadResponse>

    @GetMapping("/drivers/me/chat/messages")
    @Operation(operationId = "listMyChatMessages", summary = "DRIVER: 본인 대화 내역", description = "기본 최신순. before와 after는 동시에 지정할 수 없다.")
    fun myMessages(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal?,
        @RequestParam(required = false) @Size(max = 32) before: String?,
        @RequestParam(required = false) @Size(max = 32) after: String?,
        @RequestParam(defaultValue = "30") @Min(1) @Max(100) limit: Int,
    ): ResponseEntity<ChatMessagePageResponse>

    @PostMapping("/drivers/me/chat/messages", consumes = ["application/json"])
    @Operation(operationId = "sendMyChatMessage", summary = "DRIVER: 관리자에게 메시지 전송")
    @ApiResponse(responseCode = "201", description = "저장 완료. 동일 키·본문 재전송 시 최초 결과.")
    @ApiResponse(responseCode = "409", description = "같은 키를 다른 본문으로 사용했다.")
    fun mySend(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal?,
        @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 200) key: String,
        @Valid @RequestBody body: SendChatMessage,
    ): ResponseEntity<ChatMessageResponse>

    @PutMapping("/drivers/me/chat/read", consumes = ["application/json"])
    @Operation(operationId = "markMyChatRead", summary = "DRIVER: 본인 읽음 위치 전진")
    fun myMarkRead(
        @Parameter(hidden = true) @AuthenticationPrincipal principal: AuthPrincipal?,
        @Valid @RequestBody body: MarkChatRead,
    ): ResponseEntity<ChatReadResponse>
}
