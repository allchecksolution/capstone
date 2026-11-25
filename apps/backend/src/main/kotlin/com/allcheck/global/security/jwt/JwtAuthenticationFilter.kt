package com.allcheck.global.security.jwt

import com.allcheck.global.security.exception.SecurityErrorCode
import com.allcheck.global.security.exception.TokenAuthenticationException
import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.IdentityOperations
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.filter.OncePerRequestFilter

class JwtAuthenticationFilter(
    private val tokens: JwtTokenProvider,
    private val identity: IdentityOperations,
    private val entryPoint: AuthenticationEntryPoint,
    private val credentialRequest: RequestMatcher,
    private val websocketRequest: RequestMatcher? = null,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = credentialRequest.matches(request)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        try {
            authenticate(request)
        } catch (ex: TokenAuthenticationException) {
            SecurityContextHolder.clearContext()
            entryPoint.commence(request, response, ex)
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun authenticate(request: HttpServletRequest) {
        val headers = request.getHeaders(HttpHeaders.AUTHORIZATION).toList()
        val protocolTokens = if (websocketRequest?.matches(request) == true && request.getHeader(HttpHeaders.UPGRADE).equals("websocket", ignoreCase = true)) {
            request.getHeaders("Sec-WebSocket-Protocol").toList().flatMap { it.split(',') }
                .map(String::trim).filter { it.startsWith("bearer.") }.map { it.removePrefix("bearer.") }
        } else {
            emptyList()
        }
        val token = when {
            headers.isEmpty() && protocolTokens.isEmpty() -> return
            headers.size == 1 && protocolTokens.isEmpty() -> BEARER_HEADER.matchEntire(headers.single())?.groupValues?.get(1)
            headers.isEmpty() && protocolTokens.size == 1 -> protocolTokens.single().takeIf { it.isNotBlank() }
            else -> null
        } ?: throw TokenAuthenticationException(SecurityErrorCode.INVALID_TOKEN)
        val claims = try {
            tokens.parseClaims(token)
        } catch (ex: ExpiredJwtException) {
            throw TokenAuthenticationException(SecurityErrorCode.TOKEN_EXPIRED, ex)
        } catch (ex: JwtException) {
            throw TokenAuthenticationException(SecurityErrorCode.INVALID_TOKEN, ex)
        }
        val accountId = claims.subject.toLongOrNull()?.takeIf { it > 0 }
            ?: throw TokenAuthenticationException(SecurityErrorCode.INVALID_TOKEN)
        val sessionId = claims.get("sid", String::class.java).toLongOrNull()?.takeIf { it > 0 }
            ?: throw TokenAuthenticationException(SecurityErrorCode.INVALID_TOKEN)
        val account = identity.findAuthenticatedAccount(accountId, sessionId)
            ?: throw TokenAuthenticationException(SecurityErrorCode.INVALID_TOKEN)
        val principal = AuthPrincipal(account.accountId, sessionId, account.userType, account.role)
        // MASTER/SUB는 동일한 ADMIN 권한을 가진다. 역할 값은 principal에 보존한다.
        val authorities = listOf(SimpleGrantedAuthority("ROLE_${account.userType.name}"))
        val authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities)
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        request.setAttribute(TOKEN_EXPIRATION_ATTRIBUTE, claims.expiration.toInstant())
    }

    companion object {
        const val TOKEN_EXPIRATION_ATTRIBUTE = "accessTokenExpiration"
        private val BEARER_HEADER = Regex("Bearer +([^\\s,]+)", RegexOption.IGNORE_CASE)
    }
}
