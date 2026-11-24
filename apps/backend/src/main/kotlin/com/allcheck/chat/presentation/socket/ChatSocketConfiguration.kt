package com.allcheck.chat.presentation.socket

import com.allcheck.global.security.jwt.JwtAuthenticationFilter
import com.allcheck.global.security.principal.AuthPrincipal
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.server.HandshakeInterceptor
import java.time.Instant

@Configuration
@EnableWebSocket
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
internal class ChatSocketConfiguration(
    private val handler: ChatSocketHandler,
    @Value("\${chat.websocket.allowed-origins:}") private val allowedOrigins: String,
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        val origins = allowedOrigins.split(',').map(String::trim).filter(String::isNotEmpty)
        require(origins.none { '*' in it }) { "Chat WebSocket origins must be explicit." }
        registry.addHandler(handler, "/api/v1/chat/ws")
            .addInterceptors(ChatSocketHandshake())
            .setAllowedOrigins(*origins.toTypedArray())
    }
}

internal data class ChatSocketIdentity(val principal: AuthPrincipal, val expiresAt: Instant)

private class ChatSocketHandshake : HandshakeInterceptor {

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthPrincipal
        val expiration = (request as? ServletServerHttpRequest)?.servletRequest
            ?.getAttribute(JwtAuthenticationFilter.TOKEN_EXPIRATION_ATTRIBUTE) as? Instant
        if (principal == null || expiration == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED)
            return false
        }
        attributes[ChatSocketHandler.IDENTITY_ATTRIBUTE] = ChatSocketIdentity(principal, expiration)
        return true
    }

    override fun afterHandshake(request: ServerHttpRequest, response: ServerHttpResponse, wsHandler: WebSocketHandler, exception: Exception?) = Unit
}
