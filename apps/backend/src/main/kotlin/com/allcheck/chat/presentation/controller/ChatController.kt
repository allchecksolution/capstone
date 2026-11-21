package com.allcheck.chat.presentation.controller

import com.allcheck.chat.application.ChatService
import com.allcheck.chat.presentation.api.ChatApi
import com.allcheck.chat.presentation.dto.ChatRoomPageResponse
import com.allcheck.chat.presentation.dto.MarkChatRead
import com.allcheck.chat.presentation.dto.SendChatMessage
import com.allcheck.chat.presentation.dto.parseChatMessageId
import com.allcheck.chat.presentation.dto.response
import com.allcheck.global.security.principal.AuthPrincipal
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController

@RestController
internal class ChatController(private val chat: ChatService) : ChatApi {

    @PreAuthorize("hasRole('ADMIN')")
    override fun rooms(principal: AuthPrincipal?, q: String?, page: Int, size: Int): ResponseEntity<ChatRoomPageResponse> {
        val roomPage = chat.rooms(chat.actor(principal), q, page, size)
        return ok(ChatRoomPageResponse(roomPage.rooms.map { it.response() }, page, size, roomPage.hasNext))
    }

    @PreAuthorize("hasRole('ADMIN')")
    override fun messages(principal: AuthPrincipal?, driverId: Long, before: String?, after: String?, limit: Int) = ok(chat.messages(chat.actor(principal), driverId, before?.let(::parseChatMessageId), after?.let(::parseChatMessageId), limit).response())

    @PreAuthorize("hasRole('ADMIN')")
    override fun send(principal: AuthPrincipal?, driverId: Long, key: String, body: SendChatMessage) = created(chat.send(chat.actor(principal), driverId, key, body.body).response())

    @PreAuthorize("hasRole('ADMIN')")
    override fun markRead(principal: AuthPrincipal?, driverId: Long, body: MarkChatRead) = ok(chat.markRead(chat.actor(principal), driverId, parseChatMessageId(body.lastReadMessageId)).response())

    @PreAuthorize("hasRole('DRIVER')")
    override fun myMessages(principal: AuthPrincipal?, before: String?, after: String?, limit: Int) = chat.actor(principal).let { actor ->
        ok(chat.messages(actor, chat.ownDriverId(actor), before?.let(::parseChatMessageId), after?.let(::parseChatMessageId), limit).response())
    }

    @PreAuthorize("hasRole('DRIVER')")
    override fun mySend(principal: AuthPrincipal?, key: String, body: SendChatMessage) = chat.actor(principal).let { actor ->
        created(chat.send(actor, chat.ownDriverId(actor), key, body.body).response())
    }

    @PreAuthorize("hasRole('DRIVER')")
    override fun myMarkRead(principal: AuthPrincipal?, body: MarkChatRead) = chat.actor(principal).let { actor ->
        ok(chat.markRead(actor, chat.ownDriverId(actor), parseChatMessageId(body.lastReadMessageId)).response())
    }

    private fun <T> ok(body: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private fun <T> created(body: T) = ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body)
}
