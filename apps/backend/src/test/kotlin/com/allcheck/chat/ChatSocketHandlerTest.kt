package com.allcheck.chat

import com.allcheck.chat.application.ChatService
import com.allcheck.chat.domain.vo.ChatActor
import com.allcheck.chat.domain.vo.ChatSide
import com.allcheck.chat.presentation.socket.ChatSocketHandler
import com.allcheck.chat.presentation.socket.ChatSocketIdentity
import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.AuthenticatedAccount
import com.allcheck.identity.IdentityOperations
import com.allcheck.identity.UserType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant

class ChatSocketHandlerTest {

    @Test
    fun `heartbeat가 끊기면 유효 세션도 닫히고 이후 메시지 수신 대상으로 계산되지 않는다`() {
        // given
        val start = Instant.parse("2026-11-01T00:00:00Z")
        var now = start
        val clock = mock(Clock::class.java)
        `when`(clock.instant()).thenAnswer { now }
        val principal = AuthPrincipal(10, 11, UserType.DRIVER, null)
        val actor = ChatActor(10, ChatSide.DRIVER)
        val chat = mock(ChatService::class.java)
        `when`(chat.canSubscribe(actor, 20)).thenReturn(true)
        val identity = mock(IdentityOperations::class.java)
        `when`(identity.findAuthenticatedAccount(10, 11)).thenReturn(AuthenticatedAccount(10, UserType.DRIVER, null))
        val socket = mock(WebSocketSession::class.java)
        `when`(socket.id).thenReturn("driver-socket")
        `when`(socket.isOpen).thenReturn(true)
        `when`(socket.attributes).thenReturn(mapOf(ChatSocketHandler.IDENTITY_ATTRIBUTE to ChatSocketIdentity(principal, start.plusSeconds(300))))
        val handler = ChatSocketHandler(chat, identity, JsonMapper.builder().build(), clock)
        handler.afterConnectionEstablished(socket)
        handler.handleMessage(socket, TextMessage("""{"type":"SUBSCRIBE","driverId":20}"""))

        // when
        now = start.plusSeconds(30)
        handler.handleMessage(socket, TextMessage("""{"type":"PING"}"""))
        now = start.plusSeconds(89)
        handler.closeStaleConnections()
        assertThat(handler.hasRecipient(20, ChatSide.DRIVER)).isTrue()
        verify(socket, never()).close(CloseStatus.POLICY_VIOLATION)
        now = start.plusSeconds(90)
        handler.closeStaleConnections()

        // then
        verify(socket).close(CloseStatus.POLICY_VIOLATION)
        assertThat(handler.hasRecipient(20, ChatSide.DRIVER)).isFalse()
        assertThat(handler.publish(1, 20, ChatSide.ADMIN)).isFalse()
    }
}
