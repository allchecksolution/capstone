package com.allcheck.reception.presentation

import com.allcheck.reception.ReceptionAdministratorIdentity
import com.allcheck.reception.application.ReceptionAnalysisTransactions
import com.allcheck.reception.application.ReceptionRecordingTransactions
import com.allcheck.reception.application.RecordingUploadMetadata
import com.allcheck.reception.domain.vo.ReceptionAiOriginal
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.domain.vo.ReceptionStructuredResult
import com.allcheck.reception.domain.vo.WorkType
import com.allcheck.support.PostgresTestSchema
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest(properties = ["springdoc.api-docs.enabled=true", "spring.jpa.show-sql=false"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource("classpath:jwt-test.properties")
@Import(ReceptionApiIntegrationTest.IdentityFixture::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReceptionApiIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val mapper: ObjectMapper,
    private val recording: ReceptionRecordingTransactions,
    private val analysis: ReceptionAnalysisTransactions,
    private val dataSource: DataSource,
) {
    @Test
    fun `미인증은 401 기사 권한은 입력 검증 전 403이며 관리자 식별자를 본문에서 받지 않는다`() {
        // when & then
        mvc.perform(get("/api/v1/receptions")).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        mvc.perform(
            post("/api/v1/receptions").with(user("driver").roles("DRIVER"))
                .contentType(MediaType.APPLICATION_JSON).content("not-json"),
        )
            .andExpect(status().isForbidden).andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        mvc.perform(get("/api/v1/receptions").with(user("unresolved").roles("ADMIN")))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `실제 HTTP 검토 흐름에서 원본을 보존하고 성공 재시도와 최신값 조회를 구분한다`() {
        // given
        val id = create()
        val numeric = ReceptionExternalId.RECEPTION.parse(id)
        val meta = RecordingUploadMetadata("call.m4a", "audio/mp4", 1024, ByteArray(32) { 1 })
        val reserved = recording.reserve(1, numeric, 1, meta, "test-bucket")
        val accepted = recording.complete(1, numeric, reserved.recordingId, 2, meta)
        mvc.perform(get("/api/v1/receptions/$id").with(admin(1)))
            .andExpect(status().isOk).andExpect(jsonPath("$.reviewEditable").value(false))
        analysis.claim(accepted.analysisId, "api-worker", Duration.ofMinutes(1))
        analysis.transcribed(accepted.analysisId, "api-worker", ORIGINAL, "api-trace")
        analysis.storeTranscript(accepted.analysisId, "api-worker", ORIGINAL, "api-trace")
        analysis.startStructuring(accepted.analysisId, "api-worker", "api-trace")
        analysis.structured(accepted.analysisId, "api-worker", ReceptionStructuredResult(ReceptionAiOriginal(workType = WorkType.BLOCKAGE, notes = "AI 원본 메모", desiredVisitFrom = java.time.OffsetDateTime.parse("2026-11-18T14:00:00+09:00"), desiredVisitTo = java.time.OffsetDateTime.parse("2026-11-18T16:00:00+09:00")), emptyList()), "api-trace")
        analysis.apply(accepted.analysisId, "api-worker", "api-trace")
        val confirmKey = key()
        val body = confirmation(4, "관리자 메모")

        // when
        mvc.perform(
            post("/api/v1/receptions/$id/confirmation").with(admin(1))
                .header("Idempotency-Key", confirmKey).contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isOk).andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(jsonPath("$.version").value(5)).andExpect(jsonPath("$.finalData.notes").value("관리자 메모"))
        mvc.perform(
            post("/api/v1/receptions/$id/reopen").with(admin(1)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":5}"),
        )
            .andExpect(status().isOk).andExpect(jsonPath("$.version").value(6))
            .andExpect(jsonPath("$.confirmedAt").isEmpty())
        mvc.perform(
            post("/api/v1/receptions/$id/confirmation").with(admin(1)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(confirmation(6, null)),
        )
            .andExpect(status().isOk).andExpect(jsonPath("$.version").value(7))
            .andExpect(jsonPath("$.finalData.notes").doesNotExist())
        mvc.perform(
            post("/api/v1/receptions/$id/confirmation").with(admin(1)).header("Idempotency-Key", confirmKey)
                .contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isOk).andExpect(header().string("Idempotency-Replayed", "true"))
            .andExpect(jsonPath("$.version").value(5)).andExpect(jsonPath("$.finalData.notes").value("관리자 메모"))

        // then
        mvc.perform(get("/api/v1/receptions/$id").with(admin(1))).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.version").value(7))
            .andExpect(jsonPath("$.transcript").value(ORIGINAL))
            .andExpect(jsonPath("$.aiOriginal.notes").value("AI 원본 메모"))
            .andExpect(jsonPath("$.aiOriginal.desiredVisitFrom").value("2026-11-18T14:00:00+09:00"))
            .andExpect(jsonPath("$.finalData.notes").doesNotExist())
            .andExpect(jsonPath("$.finalData.contactPhone").value("010-1234-5678"))
            .andExpect(jsonPath("$.callerPhoneMasked").value("***-****-2222"))
            .andExpect(jsonPath("$.objectKey").doesNotExist())
        val attempt = ReceptionExternalId.ANALYSIS.format(accepted.analysisId)
        mvc.perform(get("/api/v1/receptions/$id/analyses/$attempt").with(admin(1)))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.receptionVersion").value(3))
            .andExpect(jsonPath("$.aiOriginal.notes").value("AI 원본 메모"))
        mvc.perform(get("/api/v1/receptions/$id").with(admin(2)))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("RECEPTION_NOT_FOUND"))
        mvc.perform(get("/api/v1/receptions/$id/analyses/$attempt").with(admin(2)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `생성 재시도는 동일 Location과 빈 초안을 반환하고 잘못된 헤더 및 페이지는 거부한다`() {
        // given
        val key = key()
        val request = { post("/api/v1/receptions").with(admin(1)).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{}") }
        val first = mvc.perform(request()).andExpect(status().isCreated).andExpect(jsonPath("$.finalData").isEmpty())
            .andReturn().response

        // when & then
        val replay = mvc.perform(request()).andExpect(status().isCreated).andExpect(header().string("Idempotency-Replayed", "true"))
            .andReturn().response
        assertThat(mapper.readTree(replay.contentAsString)).isEqualTo(mapper.readTree(first.contentAsString))
        assertThat(replay.getHeader("Location")).isEqualTo(first.getHeader("Location"))
        mvc.perform(post("/api/v1/receptions").with(admin(1)).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/receptions").with(admin(1)).param("page", "0")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/receptions").with(admin(1)).param("size", "101")).andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/receptions/file_rec_1").with(admin(1))).andExpect(status().isBadRequest)
    }

    @Test
    fun `생성된 OpenAPI는 구현된 접수 경로와 JWT 및 원본 조회 응답을 문서화한다`() {
        // when & then
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
            .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
            .andExpect(jsonPath("$.paths['/api/v1/receptions'].post.responses['201']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/receptions/{receptionId}'].patch").doesNotExist())
            .andExpect(jsonPath("$.paths['/api/v1/receptions/{receptionId}'].get.responses['200'].content['application/json'].schema").exists())
            .andExpect(jsonPath("$.components.schemas.ConfirmedReception.properties.finalData").exists())
            .andExpect(jsonPath("$.components.schemas.ReceptionDetail.properties.transcript").exists())
            .andExpect(jsonPath("$.components.schemas.ReceptionDetail.properties.reviewEditable").exists())
            .andExpect(jsonPath("$.components.schemas.ReceptionDetail.properties.ownerAdminId").doesNotExist())
            .andExpect(jsonPath("$.components.schemas.ReceptionConfirmationFields.properties.workType.enum", hasSize<Any>(5)))
    }

    @AfterAll
    fun removeOwnedSchema() {
        database.remove(dataSource)
    }

    private fun create(): String {
        val response = mvc.perform(
            post("/api/v1/receptions").with(admin(1)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content("{\"callerPhone\":\"010-1111-2222\"}"),
        )
            .andExpect(status().isCreated).andReturn().response
        return mapper.readTree(response.contentAsString).get("receptionId").asString()
    }

    private fun confirmation(version: Int, notes: String?): String = mapper.writeValueAsString(
        mapOf(
            "expectedVersion" to version,
            "finalData" to buildMap<String, Any> {
                put("workType", "BOILER")
                put("symptoms", "관리자 수정 증상")
                put("address", "서울시 강남구 예시로 10")
                put("desiredVisitFrom", "2026-11-18T14:00:00+09:00")
                put("desiredVisitTo", "2026-11-18T16:00:00+09:00")
                put("contactPhone", "010-1234-5678")
                if (notes != null) put("notes", notes)
            },
        ),
    )

    private fun key() = UUID.randomUUID().toString()
    private fun admin(id: Long) = authentication(UsernamePasswordAuthenticationToken.authenticated(TestAdmin(id), null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")))
    data class TestAdmin(val id: Long)

    @TestConfiguration(proxyBeanMethods = false)
    class IdentityFixture {
        @Bean
        fun administratorIdentity() = ReceptionAdministratorIdentity { authentication -> (authentication.principal as? TestAdmin)?.id }

        @Bean
        @Primary
        fun receptionApiClock(): Clock = Clock.fixed(Instant.parse("2026-11-17T06:00:00Z"), ZoneOffset.UTC)
    }

    companion object {
        private const val ORIGINAL = "  고객: 방문 전 연락 주세요.\r\n관리자: 확인했습니다. 😀  "
        private val database = PostgresTestSchema("reception_http")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            database.register(registry)
        }
    }
}
