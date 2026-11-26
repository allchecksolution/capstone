package com.allcheck.reception.presentation

import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.identity.AdminRole
import com.allcheck.identity.UserType
import com.allcheck.reception.application.analysis.ReceptionAnalysisTransactions
import com.allcheck.reception.application.recording.ReceptionRecordingTransactions
import com.allcheck.reception.application.recording.RecordingUploadMetadata
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.domain.vo.analysis.ReceptionAiOriginal
import com.allcheck.reception.domain.vo.analysis.ReceptionStructuredResult
import com.allcheck.reception.domain.vo.review.WorkType
import com.allcheck.support.PostgresTestSchema
import com.allcheck.support.S3TestFixtures
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
import org.springframework.jdbc.core.JdbcTemplate
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
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@SpringBootTest(properties = ["springdoc.api-docs.enabled=true", "spring.jpa.show-sql=false", "reception.recording.bucket=test-bucket"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource("classpath:jwt-test.properties")
@Import(ReceptionApiIntegrationTest.Fixtures::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReceptionApiIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val mapper: ObjectMapper,
    private val recording: ReceptionRecordingTransactions,
    private val analysis: ReceptionAnalysisTransactions,
    private val dataSource: DataSource,
    private val storageFixture: StorageFixture,
) {
    @Test
    fun `WAV 원본 파일명으로 연락처를 채우며 업로드 재요청은 예약만 재사용하고 URL을 매번 서명한다`() {
        // given
        val id = create(includeCaller = false)
        val numeric = ReceptionExternalId.RECEPTION.parse(id)
        val filename = "rec_테스트업체_01012345678_01012345678@261117_103802.wav"
        val base = "/api/v1/receptions/$id/recordings"
        val uploadKey = key()
        val body = mapper.writeValueAsString(
            mapOf(
                "expectedVersion" to 1,
                "originalFilename" to filename,
                "contentType" to "audio/wav",
                "sizeBytes" to 1024,
                "checksum" to mapOf("algorithm" to "SHA256", "value" to Base64.getEncoder().encodeToString(S3TestFixtures.DIGEST)),
            ),
        )
        fun upload(payload: String = body) = mvc.perform(post("$base/upload-requests").with(admin(1)).header("Idempotency-Key", uploadKey).contentType(MediaType.APPLICATION_JSON).content(payload))
        val before = storageFixture.signatures.get()

        // when
        val first = upload().andExpect(status().isCreated).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(jsonPath("$.requiredHeaders['Content-Type']").value("audio/wav"))
            .andExpect(jsonPath("$.requiredHeaders['Content-Length']").value("1024"))
            .andExpect(jsonPath("$.requiredHeaders['If-None-Match']").value("*"))
            .andExpect(jsonPath("$.uploadUrl").isString()).andReturn().response.contentAsString
        val file = mapper.readTree(first).get("recordingFileId").asString()
        upload().andExpect(status().isCreated).andExpect(header().string("Idempotency-Replayed", "true"))
            .andExpect(jsonPath("$.recordingFileId").value(file)).andExpect(jsonPath("$.version").value(2))
        val renewalKey = key()
        repeat(2) { mvc.perform(post("$base/$file/upload-requests").with(admin(1)).header("Idempotency-Key", renewalKey)).andExpect(status().isCreated) }

        // then: 동일 초에 서명이 같아질 수 있으므로 URL 문자열 대신 실제 서명 실행과 DB 미보관을 검사한다.
        assertThat(storageFixture.signatures.get()).isGreaterThanOrEqualTo(before + 4)
        val jdbc = JdbcTemplate(dataSource)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reception_recordings WHERE reception_id=?", Long::class.java, numeric)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT original_filename FROM reception_recordings WHERE reception_id=?", String::class.java, numeric)).isEqualTo(filename)
        assertThat(jdbc.queryForList("SELECT response_body::text FROM reception_command_receipts WHERE scope_reception_id=?", String::class.java, numeric))
            .allSatisfy { assertThat(it).doesNotContain("uploadUrl", "X-Amz", "requiredHeaders") }
        mvc.perform(get("/api/v1/receptions/$id").with(admin(1))).andExpect(status().isOk)
            .andExpect(jsonPath("$.finalData.contactPhone").value("01012345678"))
            .andExpect(jsonPath("$.callerPhoneMasked").value("*******5678"))
            .andExpect(jsonPath("$.reviewEditable").value(false)).andExpect(jsonPath("$.referenceTime").isEmpty())
        upload(body.replace("1024", "2048")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
        val source = recording.recording(1, numeric, ReceptionExternalId.RECORDING.parse(file))
        storageFixture.objects[source.objectKey] = HeadObjectResponse.builder().contentType("audio/wav").contentLength(1024)
            .checksumSHA256(Base64.getEncoder().encodeToString(S3TestFixtures.DIGEST)).build()
        mvc.perform(post("$base/$file/completion").with(admin(1)).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2}"))
            .andExpect(status().isAccepted)
        upload().andExpect(status().isConflict).andExpect(jsonPath("$.code").value("FILE_ALREADY_UPLOADED"))
        mvc.perform(post("$base/$file/upload-requests").with(admin(1)).header("Idempotency-Key", renewalKey))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("FILE_ALREADY_UPLOADED"))
    }

    @Test
    fun `WAV가 아닌 요청과 다른 소유자의 업로드는 예약을 만들지 않는다`() {
        // given
        val id = create()
        val body = mapper.writeValueAsString(
            mapOf(
                "expectedVersion" to 1,
                "originalFilename" to "call.mp3",
                "contentType" to "audio/mpeg",
                "sizeBytes" to 1024,
                "checksum" to mapOf("algorithm" to "SHA256", "value" to Base64.getEncoder().encodeToString(S3TestFixtures.DIGEST)),
            ),
        )

        // when & then
        mvc.perform(post("/api/v1/receptions/$id/recordings/upload-requests").with(admin(1)).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnsupportedMediaType).andExpect(jsonPath("$.code").value("RECORDING_FORMAT_NOT_SUPPORTED"))
        mvc.perform(post("/api/v1/receptions/$id/recordings/upload-requests").with(admin(2)).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/receptions/$id").with(admin(1))).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.recordingFileId").isEmpty())
    }

    @Test
    fun `서로 다른 키의 동시 업로드 완료도 하나의 최초 분석과 기준 시각으로 수렴한다`() {
        // given
        val id = create()
        val numeric = ReceptionExternalId.RECEPTION.parse(id)
        val metadata = RecordingUploadMetadata("call.wav", "audio/wav", 1024, S3TestFixtures.DIGEST)
        val reserved = recording.reserve(1, numeric, 1, metadata, "test-bucket")
        storageFixture.objects[reserved.objectKey] = HeadObjectResponse.builder().contentType("audio/wav").contentLength(1024)
            .checksumSHA256(Base64.getEncoder().encodeToString(metadata.checksum)).build()
        storageFixture.barriers[reserved.objectKey] = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)

        // when
        val responses = try {
            List(2) {
                executor.submit<String> {
                    mvc.perform(
                        post("/api/v1/receptions/$id/recordings/${ReceptionExternalId.RECORDING.format(reserved.recordingId)}/completion")
                            .with(admin(1)).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2}"),
                    )
                        .andExpect(status().isAccepted).andReturn().response.contentAsString
                }
            }.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            storageFixture.barriers.remove(reserved.objectKey)
            executor.shutdownNow()
        }

        // then
        assertThat(mapper.readTree(responses[0])).isEqualTo(mapper.readTree(responses[1]))
        val accepted = recording.accepted(1, numeric, reserved.recordingId)
        assertThat(accepted).isNotNull()
        mvc.perform(get("/api/v1/receptions/$id").with(admin(1))).andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(3))
            .andExpect(jsonPath("$.latestAnalysisRequestId").value(ReceptionExternalId.ANALYSIS.format(checkNotNull(accepted).analysisId)))
    }

    @Test
    fun `업로드 완료는 실제 객체를 검증하고 중복 완료는 S3 재조회 없이 최초 분석 접수를 반환한다`() {
        // given
        val id = create()
        val numeric = ReceptionExternalId.RECEPTION.parse(id)
        val metadata = RecordingUploadMetadata("call.wav", "audio/wav", 1024, S3TestFixtures.DIGEST)
        val reserved = recording.reserve(1, numeric, 1, metadata, "test-bucket")
        val fileId = ReceptionExternalId.RECORDING.format(reserved.recordingId)
        val base = "/api/v1/receptions/$id/recordings/$fileId"
        val completionKey = key()
        fun complete(key: String, version: Int = 2) = mvc.perform(
            post("$base/completion").with(admin(1)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":$version}"),
        )

        // when & then: pending 원본은 재생할 수 없고 S3 검증 실패는 분석을 생성하지 않는다.
        mvc.perform(get("$base/access").with(admin(1))).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("FILE_NOT_READY"))
        complete(completionKey).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("FILE_UPLOAD_NOT_VERIFIED"))
        mvc.perform(get("/api/v1/receptions/$id").with(admin(1))).andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.latestAnalysisRequestId").isEmpty())
        storageFixture.objects[reserved.objectKey] = HeadObjectResponse.builder().contentType("audio/wav").contentLength(1024)
            .checksumSHA256(Base64.getEncoder().encodeToString(metadata.checksum)).build()
        val first = complete(completionKey).andExpect(status().isAccepted)
            .andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.analysis.receptionVersion").value(3)).andExpect(jsonPath("$.analysis.status").value("PENDING"))
            .andReturn().response.contentAsString
        val reads = storageFixture.reads.get()
        storageFixture.objects.remove(reserved.objectKey) // 이후 S3가 접근 불가능해도 최초 접수는 취소되거나 중복 실행되지 않는다.
        val replay = complete(completionKey).andExpect(status().isAccepted).andExpect(header().string("Idempotency-Replayed", "true"))
            .andReturn().response.contentAsString
        assertThat(mapper.readTree(replay)).isEqualTo(mapper.readTree(first))
        complete(completionKey, 999).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
        val repeated = complete(key(), 999).andExpect(status().isAccepted).andReturn().response.contentAsString
        assertThat(mapper.readTree(repeated)).isEqualTo(mapper.readTree(first))
        assertThat(storageFixture.reads.get()).isEqualTo(reads)
        mvc.perform(get("$base/access").with(admin(1))).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.fileId").value(fileId)).andExpect(jsonPath("$.method").value("GET"))
            .andExpect(jsonPath("$.url").isString()).andExpect(jsonPath("$.expiresAt").isString())
            .andExpect(jsonPath("$.objectKey").doesNotExist())
        mvc.perform(get("$base/access").with(admin(2))).andExpect(status().isNotFound)
        mvc.perform(
            post("$base/completion").with(admin(2)).header("Idempotency-Key", completionKey)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2}"),
        )
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("RECEPTION_NOT_FOUND"))
    }

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
        val meta = RecordingUploadMetadata("call.wav", "audio/wav", 1024, ByteArray(32) { 1 })
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

    private fun create(includeCaller: Boolean = true): String {
        val response = mvc.perform(
            post("/api/v1/receptions").with(admin(1)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(if (includeCaller) "{\"callerPhone\":\"010-1111-2222\"}" else "{}"),
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
    private fun admin(id: Long) = authentication(
        UsernamePasswordAuthenticationToken.authenticated(AuthPrincipal(id, 1L, UserType.ADMIN, AdminRole.MASTER), null, AuthorityUtils.createAuthorityList("ROLE_ADMIN")),
    )

    @TestConfiguration(proxyBeanMethods = false)
    class Fixtures {
        @Bean
        fun storageFixture() = StorageFixture()

        @Bean
        fun offlineS3(fixture: StorageFixture): S3Client = S3TestFixtures.fakeS3 { request ->
            fixture.reads.incrementAndGet()
            fixture.barriers[request.key()]?.let {
                it.countDown()
                check(it.await(10, TimeUnit.SECONDS)) { "Concurrent recording verification did not arrive." }
            }
            fixture.objects[request.key()] ?: throw S3Exception.builder().statusCode(404).build()
        }

        @Bean(destroyMethod = "close")
        fun offlineSigner(fixture: StorageFixture): S3Presigner = S3TestFixtures.signer { fixture.signatures.incrementAndGet() }

        @Bean
        @Primary
        fun receptionApiClock(): Clock = Clock.fixed(Instant.parse("2026-11-17T06:00:00Z"), ZoneOffset.UTC)
    }

    class StorageFixture {
        val objects = ConcurrentHashMap<String, HeadObjectResponse>()
        val barriers = ConcurrentHashMap<String, CountDownLatch>()
        val reads = AtomicInteger()
        val signatures = AtomicInteger()
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
