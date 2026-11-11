package com.allcheck.global.security

import com.allcheck.global.security.jwt.JwtTokenProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource("classpath:jwt-test.properties")
@Import(SecurityConfigTest.TestController::class)
class SecurityConfigTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @ParameterizedTest
    @ValueSource(strings = ["/api/v1/security-tests/protected", "/api/v1/not-allowlisted", "/actuator/env", "/login", "/logout"])
    fun `Allowlist 이외의 경로는 인증 없이 접근하면 공통 401 응답을 반환한다`(path: String) {
        // when
        val result = mockMvc.perform(get(path))

        // then
        result.andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json("""{"code":"UNAUTHENTICATED","message":"인증이 필요합니다."}"""))
            .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
    }

    @Test
    fun `CSRF 토큰이 없는 미인증 POST 요청도 403 대신 401을 반환한다`() {
        // when
        val result = mockMvc.perform(post("/api/v1/security-tests/protected"))

        // then
        result.andExpect(status().isUnauthorized)
            .andExpect(content().json("""{"code":"UNAUTHENTICATED","message":"인증이 필요합니다."}"""))
    }

    @Test
    fun `유효한 JWT를 전달해도 요청 인증이 연결되기 전에는 401을 반환한다`() {
        // given
        val token = jwtTokenProvider.createAccessToken("test-subject")

        // when
        val result = mockMvc.perform(
            get("/api/v1/security-tests/protected").header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        )

        // then
        result.andExpect(status().isUnauthorized)
            .andExpect(content().json("""{"code":"UNAUTHENTICATED","message":"인증이 필요합니다."}"""))
    }

    @Test
    @WithMockUser
    fun `테스트 인증 객체의 POST는 CSRF 토큰 없이 접근 가능하고 세션을 생성하지 않는다`() {
        // when
        val result = mockMvc.perform(post("/api/v1/security-tests/protected"))

        // then
        val request = result.andExpect(status().isOk)
            .andExpect(content().string("ok"))
            .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
            .andReturn().request
        assertThat(request.getSession(false)).isNull()
    }

    @Test
    @WithMockUser
    fun `메서드 보안에서 권한이 부족하면 공통 403 응답을 반환한다`() {
        // when
        val result = mockMvc.perform(get("/api/v1/security-tests/restricted"))

        // then
        result.andExpect(status().isForbidden)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json("""{"code":"ACCESS_DENIED","message":"접근 권한이 없습니다."}"""))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/actuator/health",
            "/v3/api-docs",
            "/v3/api-docs/swagger-config",
            "/swagger-ui/index.html",
        ],
    )
    fun `기존 health 및 로컬 문서 엔드포인트는 인증 없이 접근할 수 있다`(path: String) {
        // when
        val result = mockMvc.perform(get(path))

        // then
        result.andExpect(status().isOk)
    }

    @Test
    fun `기존 Swagger 진입 경로도 인증 없이 UI로 이동한다`() {
        // when
        val result = mockMvc.perform(get("/swagger-ui.html"))

        // then
        result.andExpect(status().is3xxRedirection)
            .andExpect(header().string(HttpHeaders.LOCATION, "/swagger-ui/index.html"))
    }

    @RestController
    class TestController {
        @GetMapping("/api/v1/security-tests/protected")
        fun protectedGet(): String = "ok"

        @PostMapping("/api/v1/security-tests/protected")
        fun protectedPost(): String = "ok"

        @PreAuthorize("hasAuthority('TEST_ACCESS')")
        @GetMapping("/api/v1/security-tests/restricted")
        fun restricted(): String = "ok"
    }
}
