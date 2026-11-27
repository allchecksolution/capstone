package com.allcheck.global.security

import com.allcheck.driver.DriverOperations
import com.allcheck.global.security.handler.CustomAccessDeniedHandler
import com.allcheck.global.security.handler.CustomAuthenticationEntryPoint
import com.allcheck.global.security.jwt.JwtAuthenticationFilter
import com.allcheck.global.security.jwt.JwtTokenProvider
import com.allcheck.identity.IdentityOperations
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val customAuthenticationEntryPoint: CustomAuthenticationEntryPoint,
    private val customAccessDeniedHandler: CustomAccessDeniedHandler,
    private val jwtTokenProvider: JwtTokenProvider,
    private val identityOperations: IdentityOperations,
    private val driverOperations: DriverOperations,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val credentialRequests = OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/sessions/admin"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/sessions/kakao"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/tokens/refresh"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/auth/sessions/logout"),
        )
        http {
            sessionManagement {
                sessionCreationPolicy = SessionCreationPolicy.STATELESS
            }
            // TODO: 웹 클라이언트 도입 시 CORS 및 Cookie 인증의 CSRF 정책을 검토한다.
            csrf { disable() }
            formLogin { disable() }
            httpBasic { disable() }
            logout { disable() }
            authorizeHttpRequests {
                // 내부 ERROR 디스패치 허용
                authorize(DispatcherTypeRequestMatcher(DispatcherType.ERROR), permitAll)

                // Health
                authorize(HttpMethod.GET, "/actuator/health", permitAll)
                authorize(HttpMethod.GET, "/actuator/health/readiness", permitAll)

                // Swagger (로컬 프로파일에서만 활성)
                authorize(HttpMethod.GET, "/swagger-ui.html", permitAll)
                authorize(HttpMethod.GET, "/swagger-ui/**", permitAll)
                authorize(HttpMethod.GET, "/v3/api-docs", permitAll)
                authorize(HttpMethod.GET, "/v3/api-docs/**", permitAll)

                authorize(credentialRequests, permitAll)

                // 기본 인증 요구
                authorize("/api/v1/receptions", hasRole("ADMIN"))
                authorize("/api/v1/receptions/**", hasRole("ADMIN"))
                authorize("/api/v1/service-requests", hasRole("ADMIN"))
                authorize("/api/v1/service-requests/**", hasRole("ADMIN"))
                authorize("/api/v1/work-statistics", hasRole("ADMIN"))
                authorize("/api/v1/chat-rooms", hasRole("ADMIN"))
                authorize("/api/v1/chat-rooms/**", hasRole("ADMIN"))
                authorize("/api/v1/dispatch-cancellations", hasRole("ADMIN"))
                authorize("/api/v1/dispatch-cancellations/**", hasRole("ADMIN"))
                authorize("/api/v1/drivers/me/works", hasRole("DRIVER"))
                authorize("/api/v1/drivers/me/work-statistics", hasRole("DRIVER"))
                authorize("/api/v1/drivers/me/dispatch-offers", hasRole("DRIVER"))
                authorize("/api/v1/dispatch-offers/**", hasRole("DRIVER"))
                authorize(anyRequest, authenticated)
            }
            exceptionHandling {
                authenticationEntryPoint = customAuthenticationEntryPoint
                accessDeniedHandler = customAccessDeniedHandler
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(
                JwtAuthenticationFilter(
                    jwtTokenProvider,
                    identityOperations,
                    customAuthenticationEntryPoint,
                    credentialRequests,
                    PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/v1/chat/ws"),
                ),
            )
            addFilterAfter<JwtAuthenticationFilter>(DriverBusinessAccessFilter(driverOperations, customAccessDeniedHandler))
        }
        return http.build()
    }
}
