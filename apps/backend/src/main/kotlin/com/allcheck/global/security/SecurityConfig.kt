package com.allcheck.global.security

import com.allcheck.global.security.handler.CustomAccessDeniedHandler
import com.allcheck.global.security.handler.CustomAuthenticationEntryPoint
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
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val customAuthenticationEntryPoint: CustomAuthenticationEntryPoint,
    private val customAccessDeniedHandler: CustomAccessDeniedHandler,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
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

                // Swagger (로컬 프로파일에서만 활성)
                authorize(HttpMethod.GET, "/swagger-ui.html", permitAll)
                authorize(HttpMethod.GET, "/swagger-ui/**", permitAll)
                authorize(HttpMethod.GET, "/v3/api-docs", permitAll)
                authorize(HttpMethod.GET, "/v3/api-docs/**", permitAll)

                // 기본 인증 요구
                authorize(anyRequest, authenticated)
            }
            exceptionHandling {
                authenticationEntryPoint = customAuthenticationEntryPoint
                accessDeniedHandler = customAccessDeniedHandler
            }
            // TODO: JWT 요청 인증은 Custom Filter 또는 Resource Server 방식 확정 후 연결한다.
        }
        return http.build()
    }
}
