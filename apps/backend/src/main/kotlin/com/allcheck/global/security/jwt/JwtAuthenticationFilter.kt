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
        if (headers.isEmpty()) return
        if (headers.size != 1) throw TokenAuthenticationException(SecurityErrorCode.INVALID_TOKEN)
        val match = BEARER_HEADER.matchEntire(headers.single())
            ?: throw TokenAuthenticationException(SecurityErrorCode.INVALID_TOKEN)
        val claims = try {
            tokens.parseClaims(match.groupValues[1])
        } catch (ex: ExpiredJwtException) {
            throw TokenAuthenticationException(SecurityErrorCode.TOKEN_EXPIRED, ex)
        } catch (ex: JwtException) {
            throw TokenAuthenticationException(SecurityErrorCode.INVALID_TOKEN, ex)
        }
        val sessionId = claims.get("sid", String::class.java)
        val account = identity.findAuthenticatedAccount(claims.subject, sessionId)
            ?: throw TokenAuthenticationException(SecurityErrorCode.INVALID_TOKEN)
        val principal = AuthPrincipal(account.accountId, sessionId, account.userType, account.role)
        // MASTER/SUB는 동일한 ADMIN 권한을 가진다. 역할 값은 principal에 보존한다.
        val authorities = listOf(SimpleGrantedAuthority("ROLE_${account.userType.name}"))
        val authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities)
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
    }

    companion object {
        private val BEARER_HEADER = Regex("Bearer +([^\\s,]+)", RegexOption.IGNORE_CASE)
    }
}
