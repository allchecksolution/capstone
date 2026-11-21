package com.allcheck.operations

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.notification.FcmConnection
import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.global.storage.SignedObjectUrl
import com.allcheck.identity.AdminRole
import com.allcheck.identity.PushTarget
import com.allcheck.identity.UserType
import com.allcheck.operations.coordination.application.OperationsPhotos
import com.allcheck.operations.coordination.application.OperationsQueries
import com.allcheck.operations.coordination.application.OperationsReportTransactions
import com.allcheck.operations.coordination.application.OperationsService
import com.allcheck.operations.coordination.application.OperationsTaskTransactions
import com.allcheck.operations.coordination.application.OperationsTransactions
import com.allcheck.operations.coordination.application.OperationsWorker
import com.allcheck.operations.coordination.application.PushRecipient
import com.allcheck.operations.dispatch.DispatchOperations
import com.allcheck.operations.domain.entity.TaskKind
import com.allcheck.operations.infrastructure.geo.OperationsSpatial
import com.allcheck.operations.infrastructure.geo.SgisConnection
import com.allcheck.operations.infrastructure.geo.SgisGeocoder
import com.allcheck.operations.request.RequestOperations
import com.allcheck.operations.work.PhotoMetadata
import com.allcheck.operations.work.PhotoSnapshot
import com.allcheck.operations.work.PhotoStorageOperations
import com.allcheck.operations.work.ReportOperations
import com.allcheck.operations.work.WorkOperations
import com.allcheck.reception.application.review.ReceptionService
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.domain.vo.review.ReceptionConfirmationData
import com.allcheck.reception.domain.vo.review.ReceptionCreationData
import com.allcheck.support.PostgresTestSchema
import com.allcheck.support.insertDriverFixture
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.support.StaticListableBeanFactory
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest(properties = ["spring.jpa.show-sql=false", "springdoc.api-docs.enabled=true"])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource("classpath:jwt-test.properties")
@Import(OperationsIntegrationTest.Fixtures::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OperationsIntegrationTest @Autowired constructor(
    private val receptions: ReceptionService,
    private val service: OperationsService,
    private val transactions: OperationsTransactions,
    private val tasks: OperationsTaskTransactions,
    private val dispatch: DispatchOperations,
    private val requests: RequestOperations,
    private val works: WorkOperations,
    private val reports: OperationsReportTransactions,
    private val reportData: ReportOperations,
    private val queries: OperationsQueries,
    private val photos: OperationsPhotos,
    private val photoStorage: PhotoFixture,
    private val mvc: MockMvc,
    private val worker: OperationsWorker,
    private val pushFixture: PushFixture,
    private val spatial: OperationsSpatial,
    private val clock: MutableClock,
    private val geo: GeocoderFixture,
    private val dataSource: DataSource,
) {
    private val admin = OperationsActor(ActorRole.ADMIN, 1)
    private val jdbc get() = JdbcTemplate(dataSource)

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE receptions,operations_receipts RESTART IDENTITY CASCADE")
        jdbc.insertDriverFixture(10)
        jdbc.insertDriverFixture(20)
        clock.time = Instant.parse("2026-10-31T14:59:00Z")
        geo.fail = false
        geo.barrier = null
        photoStorage.fail = false
        pushFixture.messages.clear()
        pushFixture.bindings.clear()
    }

    @AfterAll
    fun cleanup() = schema.remove(dataSource)

    @Test
    fun `좌표 실패는 접수 확정과 버전을 보존하고 등록 재시도는 회차와 의뢰를 중복 생성하지 않는다`() {
        // given
        val (receptionId, version) = confirmed()
        geo.fail = true
        // when
        assertThrows<BusinessException> { service.register(admin, receptionId, version, "register") }
        // then
        assertThat(count("service_requests")).isZero()
        assertThat(jdbc.queryForObject("select version from receptions where reception_id=?", Int::class.java, receptionId)).isEqualTo(version)
        geo.fail = false
        val first = service.register(admin, receptionId, version, "register")
        geo.fail = true
        assertThat(service.register(admin, receptionId, version, "register").value).isEqualTo(first.value)
        assertThat(count("service_requests")).isEqualTo(1)
        assertThat(count("dispatch_rounds")).isEqualTo(1)
        assertThat(jdbc.queryForObject("select request_id from receptions where reception_id=?", Long::class.java, receptionId)).isEqualTo(first.value.requestId)
    }

    @Test
    fun `서로 다른 키로 같은 접수를 동시에 등록해도 한 의뢰만 생성하며 패자는 업무 충돌이다`() {
        // given
        val (id, version) = confirmed()
        geo.barrier = java.util.concurrent.CyclicBarrier(2)
        // when
        val results = race(listOf({ service.register(admin, id, version, "register-a") }, { service.register(admin, id, version, "register-b") }))
        // then
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.single { it.isFailure }.exceptionOrNull()).isInstanceOf(BusinessException::class.java)
        assertThat(count("service_requests")).isEqualTo(1)
        assertThat(count("dispatch_rounds")).isEqualTo(1)
    }

    @Test
    fun `확정 뒤 방문 시간이 지나도 확정 스냅샷을 의뢰로 등록할 수 있다`() {
        // given
        val (id, version) = confirmed()
        clock.time = clock.time.plusSeconds(86400)
        // when
        val result = service.register(admin, id, version, key())
        // then
        assertThat(requests.get(result.value.requestId).status).isEqualTo(RequestStatus.OPEN)
    }

    @Test
    fun `서로 다른 기사의 동시 수락은 한 작업과 한 수락만 커밋한다`() {
        // given
        val request = registered()
        search(listOf(profile(10), profile(20)))
        val offers = dispatch.offers(checkNotNull(requests.get(request).currentRoundId))
        // when
        val results = race(offers.map { offer -> { transactions.respond(OperationsActor(ActorRole.DRIVER, offer.driverId), offer.offerId, key(), true) } })
        // then
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(count("work_orders")).isEqualTo(1)
        assertThat(dispatch.offers(offers.first().roundId).count { it.status == OfferStatus.ACCEPTED }).isEqualTo(1)
        assertThat(requests.get(request).status).isEqualTo(RequestStatus.ACCEPTED)
    }

    @Test
    fun `한 기사가 다른 의뢰를 동시에 수락해도 진행 중 작업은 한 건이다`() {
        // given
        val first = registered()
        search(listOf(profile(10)))
        val second = registered()
        search(listOf(profile(10)))
        val offers = listOf(first, second).map { dispatch.offers(checkNotNull(requests.get(it).currentRoundId)).single() }
        // when
        val results = race(offers.map { offer -> { transactions.respond(OperationsActor(ActorRole.DRIVER, 10), offer.offerId, key(), true) } })
        // then
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(count("work_orders")).isEqualTo(1)
        assertThat(listOf(first, second).count { requests.get(it).status == RequestStatus.ACCEPTED }).isEqualTo(1)
    }

    @Test
    fun `취소와 재방문 경합 및 후속 결정 경합은 부분 변경 없이 하나만 성공한다`() {
        // given
        val requestId = registered()
        search(listOf(profile(10)))
        val workId = accept(requestId, 10)
        val driver = OperationsActor(ActorRole.DRIVER, 10)
        progress(workId, driver)
        // when
        val results = race(
            listOf(
                { transactions.cancel(driver, workId, "cancel", 4, CancellationReason.OTHER, "고객 일정 변경") },
                { transactions.transition(driver, workId, "revisit", 4, WorkStatus.REVISIT_REQUIRED, "부품", null) },
            ),
        )
        // then
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        val work = works.get(workId)
        if (work.status != WorkStatus.CANCELLED) transactions.cancel(driver, workId, "cancel-new", work.version, CancellationReason.SCHEDULE_CONFLICT, "방문 조정")
        val request = requests.get(requestId)
        val decisionResults = race(
            listOf(
                { transactions.decide(admin, requestId, "redispatch", request.version, checkNotNull(request.followUpTarget), FollowUpChoice.REDISPATCH) },
                { transactions.decide(OperationsActor(ActorRole.ADMIN, 2), requestId, "cancel-request", request.version, checkNotNull(request.followUpTarget), FollowUpChoice.CANCEL_REQUEST) },
            ),
        )
        assertThat(decisionResults.count { it.isSuccess }).isEqualTo(1)
        assertThat(count("operations_follow_up_decisions")).isEqualTo(1)
        assertThat(count("work_cancellations")).isEqualTo(1)
        assertThat(works.excluded(requestId)).containsExactly(10L)
    }

    @Test
    fun `검색 오류는 반경을 넓히지 않으며 재시작 후에도 세 번의 시도를 보존한다`() {
        // given
        val requestId = registered()
        // when
        repeat(3) { attempt ->
            val claim = checkNotNull(tasks.claim())
            assertThat(claim.kind).isEqualTo(TaskKind.SEARCH)
            assertThat(claim.attempts).isEqualTo(attempt + 1)
            tasks.searched(claim, null, true)
            if (attempt < 2) {
                assertThat(tasks.claim()).isNull()
                clock.time = clock.time.plusSeconds(if (attempt == 0) 5 else 10)
            }
        }
        // then
        val request = requests.get(requestId)
        assertThat(request.status).isEqualTo(RequestStatus.FOLLOW_UP_REQUIRED)
        assertThat(dispatch.round(checkNotNull(request.currentRoundId)).radiusKm).isEqualTo(10)
        assertThat(request.followUpReason).isEqualTo("SEARCH_FAILED")
        assertThat(count("dispatch_offers")).isZero()
    }

    @Test
    fun `오분 위치 경계와 빈 반경 확장 및 정확한 일분 만료를 적용한다`() {
        // given
        val requestId = registered()
        val start = clock.time
        // when
        search(listOf(profile(10).copy(longitude = 127.2), profile(20).copy(locationAt = start.minusSeconds(301))))
        assertThat(dispatch.round(checkNotNull(requests.get(requestId).currentRoundId)).radiusKm).isEqualTo(30)
        search(listOf(profile(10).copy(longitude = 127.2, locationAt = start.minusSeconds(300))))
        val offer = dispatch.offers(checkNotNull(requests.get(requestId).currentRoundId)).single()
        clock.time = offer.expiresAt
        assertThrows<BusinessException> { transactions.respond(OperationsActor(ActorRole.DRIVER, 10), offer.offerId, key(), true) }
        tasks.expireRequest(requestId)
        // then
        assertThat(requests.get(requestId).followUpReason).isEqualTo("ROUND_CLOSED")
        assertThat(count("work_orders")).isZero()
    }

    @Test
    fun `사진 검증 실패는 업로드를 확정하지 않고 보고서 중복 제출은 한 번만 완료한다`() {
        // given
        val request = registered()
        search(listOf(profile(10)))
        val workId = accept(request, 10)
        val driver = OperationsActor(ActorRole.DRIVER, 10)
        progress(workId, driver)
        val grant = photos.reserve(driver, workId, "photo-pending", metadata(PhotoPhase.BEFORE)).first
        val pending = grant.photoFileId.removePrefix("photo_").toLong()
        photoStorage.fail = true
        assertThrows<BusinessException> { photos.complete(driver, workId, pending, "verify-fail") }
        assertThat(reportData.photo(pending).status).isEqualTo("PENDING")
        assertThat(works.get(workId).status).isEqualTo(WorkStatus.IN_PROGRESS)
        photoStorage.fail = false
        val before = uploaded(workId, driver, PhotoPhase.BEFORE)
        val after = uploaded(workId, driver, PhotoPhase.AFTER)
        assertThrows<BusinessException> { reports.submit(driver, workId, key(), 4, ReportContent("수리", "진단", 1000), before, before) }
        assertThat(count("work_reports")).isZero()
        // when
        val results = race(listOf("report-a", "report-b").map { key -> { reports.submit(driver, workId, key, 4, ReportContent("수리", "진단", 1000), before, after) } })
        // then
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(count("work_reports")).isEqualTo(1)
        assertThat(works.get(workId).status).isEqualTo(WorkStatus.WORK_DONE)
        assertThat(requests.get(request).status).isEqualTo(RequestStatus.COMPLETED)
        val index = results.indexOfFirst { it.isSuccess }
        val original = results[index].getOrThrow()
        assertThat(reports.submit(driver, workId, if (index == 0) "report-a" else "report-b", 4, ReportContent("수리", "진단", 1000), before, after).value).isEqualTo(original.value)
        assertThat(queries.work(driver, workId).contactPhone).isNull()
    }

    @Test
    fun `보고서 완료와 취소 경합은 의뢰 작업 보고서 취소 이력을 함께 확정한다`() {
        // given
        val request = registered()
        search(listOf(profile(10)))
        val workId = accept(request, 10)
        val driver = OperationsActor(ActorRole.DRIVER, 10)
        progress(workId, driver)
        val before = uploaded(workId, driver, PhotoPhase.BEFORE)
        val after = uploaded(workId, driver, PhotoPhase.AFTER)
        // when
        val results = race(
            listOf(
                { reports.submit(driver, workId, "report", 4, ReportContent("수리", "진단", 0), before, after) },
                { transactions.cancel(driver, workId, "cancel", 4, CancellationReason.OTHER, "일정 협의") },
            ),
        )
        // then
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        val saved = works.get(workId)
        if (saved.status == WorkStatus.WORK_DONE) {
            assertThat(requests.get(request).status).isEqualTo(RequestStatus.COMPLETED)
            assertThat(count("work_reports")).isEqualTo(1)
            assertThat(count("work_cancellations")).isZero()
        } else {
            assertThat(requests.get(request).status).isEqualTo(RequestStatus.FOLLOW_UP_REQUIRED)
            assertThat(count("work_reports")).isZero()
            assertThat(count("work_cancellations")).isEqualTo(1)
        }
    }

    @Test
    fun `월 경계 완료와 수정은 최초 완료 집계를 유지하며 이전 기사에게 이후 작업을 숨긴다`() {
        // given: 10월 서울 23시59분 배정, 11월 서울 0시 완료
        val request = registered()
        search(listOf(profile(10)))
        val workId = accept(request, 10)
        val driver = OperationsActor(ActorRole.DRIVER, 10)
        progress(workId, driver)
        val before = uploaded(workId, driver, PhotoPhase.BEFORE)
        val after = uploaded(workId, driver, PhotoPhase.AFTER)
        clock.time = Instant.parse("2026-10-31T15:00:00Z")
        val reportId = reports.submit(driver, workId, "report", 4, ReportContent("원본", "초진", 1000), before, after).value.resourceId
        // when
        val revisions = race(
            listOf(
                { reports.revise(admin, reportId, "revision-a", 1, "진단 보완", null, "확진", 2000) },
                { reports.revise(OperationsActor(ActorRole.ADMIN, 2), reportId, "revision-b", 1, "진단 보완", null, "확진", 2000) },
            ),
        )
        assertThat(revisions.count { it.isSuccess }).isEqualTo(1)
        // then
        assertThat(queries.statistics(driver, "2026-10").assignments).isEqualTo(1)
        assertThat(queries.statistics(driver, "2026-10").completed).isZero()
        val november = queries.statistics(driver, "2026-11")
        assertThat(november.assignments).isZero()
        assertThat(november.completed).isEqualTo(1)
        assertThat(november.workTypes.single { it.workType == WorkType.BOILER }.ratio).isEqualTo(1.0)
        assertThat(queries.driverWorks(driver, "HISTORY", "2026-11", 1, 20).items).hasSize(1)
        assertThat(queries.driverWorks(driver, "HISTORY", "2026-10", 1, 20).items).isEmpty()
        val report = queries.report(driver, reportId)
        assertThat(report.original.diagnosis).isEqualTo("초진")
        assertThat(report.latest.diagnosis).isEqualTo("확진")
        assertThat(report.revisions.single().reason).isEqualTo("진단 보완")
        assertThat(report.submittedAt).isEqualTo(clock.time)
    }

    @Test
    fun `대시보드는 동일 과거 작업에서 기사와 상태를 찾고 최신 보고 비용을 의뢰당 한 번 집계한다`() {
        // given: 서울 10월 말 등록, 첫 기사 취소와 재배차 후 11월에 완료
        val request = registered()
        search(listOf(profile(10)))
        val oldWork = accept(request, 10)
        transactions.cancel(OperationsActor(ActorRole.DRIVER, 10), oldWork, "cancel", 1, CancellationReason.OTHER, "이동 불가")
        val waiting = requests.get(request)
        transactions.decide(admin, request, "redispatch", waiting.version, checkNotNull(waiting.followUpTarget), FollowUpChoice.REDISPATCH)
        search(listOf(profile(20)))
        val newWork = accept(request, 20)
        val driver = OperationsActor(ActorRole.DRIVER, 20)
        progress(newWork, driver)
        val before = uploaded(newWork, driver, PhotoPhase.BEFORE)
        val after = uploaded(newWork, driver, PhotoPhase.AFTER)
        clock.time = Instant.parse("2026-10-31T15:00:00Z")
        val report = reports.submit(driver, newWork, "report", 4, ReportContent("수리", "진단", 1000), before, after).value.resourceId
        reports.revise(admin, report, "revise", 1, "비용 정정", null, null, 3000)
        registered()

        // when & then
        mvc.perform(
            get("/api/v1/service-requests").param("from", "2026-10-31").param("to", "2026-10-31")
                .param("driverId", "10").param("workStatus", "CANCELLED").param("region", "서울 중구")
                .param("workType", "BOILER").param("q", "req_$request").with(authenticated(1, UserType.ADMIN)),
        ).andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].currentDriverId").value(20))
            .andExpect(jsonPath("$.items[0].currentWorkStatus").value("WORK_DONE"))
        mvc.perform(
            get("/api/v1/service-requests").param("driverId", "10").param("workStatus", "WORK_DONE")
                .with(authenticated(1, UserType.ADMIN)),
        ).andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(0))
        mvc.perform(
            get("/api/v1/work-statistics").param("from", "2026-10-25").param("to", "2026-10-31")
                .param("driverId", "10").param("workStatus", "CANCELLED").with(authenticated(2, UserType.ADMIN)),
        ).andExpect(status().isOk).andExpect(jsonPath("$.metrics.totalRequests").value(1))
            .andExpect(jsonPath("$.metrics.completedRequests").value(1))
            .andExpect(jsonPath("$.metrics.cancelledRequests").value(0))
            .andExpect(jsonPath("$.metrics.totalCostKrw").value(3000))
            .andExpect(jsonPath("$.metrics.averageCostKrw").value(3000))
            .andExpect(jsonPath("$.metrics.weeklyCounts[0].from").value("2026-10-25"))
            .andExpect(jsonPath("$.metrics.workTypes.length()").value(5))
        mvc.perform(
            get("/api/v1/work-statistics").param("from", "2026-11-01").param("to", "2026-11-01")
                .param("driverId", "10").with(authenticated(1, UserType.ADMIN)),
        ).andExpect(status().isOk).andExpect(jsonPath("$.hasData").value(false)).andExpect(jsonPath("$.metrics").isEmpty)
        mvc.perform(get("/api/v1/service-requests").param("status", "OPEN").with(authenticated(1, UserType.ADMIN)))
            .andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(1))
        mvc.perform(get("/api/v1/work-statistics").with(authenticated(10, UserType.DRIVER))).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/work-statistics").param("from", "2026-11-01").param("to", "2026-10-31").with(authenticated(1, UserType.ADMIN)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `HTTP 인증 역할 자원 권한과 생성 OpenAPI를 검증한다`() {
        // given
        val request = registered()
        search(listOf(profile(10)))
        val offer = dispatch.offers(checkNotNull(requests.get(request).currentRoundId)).single()
        // when & then
        mvc.perform(get("/api/v1/service-requests/req_$request")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/service-requests/req_$request").with(authenticated(10, UserType.DRIVER))).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/dispatch-offers/offer_${offer.offerId}").with(authenticated(20, UserType.DRIVER))).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/dispatch-offers/offer_${offer.offerId}").with(authenticated(10, UserType.DRIVER)))
            .andExpect(status().isOk).andExpect(jsonPath("$.addressArea").value("서울특별시 중구 가상동"))
            .andExpect(jsonPath("$.contactPhone").doesNotExist()).andExpect(jsonPath("$.address").doesNotExist())
        mvc.perform(get("/api/v1/drivers/me/works").param("month", "2026-11").with(authenticated(10, UserType.DRIVER))).andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/service-requests/req_$request").with(authenticated(2, UserType.ADMIN))).andExpect(status().isOk)
        mvc.perform(get("/api/v1/service-requests/req_$request").with(user("unmapped").roles("ADMIN"))).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/works/work_1").with(authenticated(2, UserType.ADMIN, authority = UserType.DRIVER))).andExpect(status().isForbidden)
        mvc.perform(get("/api/v1/drivers/me/work-statistics").param("month", "2026-11").with(authenticated(99, UserType.DRIVER))).andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/dispatch-offers/offer_${offer.offerId}/response").with(authenticated(10, UserType.DRIVER)).header("Idempotency-Key", "bad").contentType(MediaType.APPLICATION_JSON).content("{\"response\":\"OTHER\"}"))
            .andExpect(status().isBadRequest)
        val api = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/api/v1/works/{workId}/reports'].post.operationId").value("submitWorkReport"))
            .andExpect(jsonPath("$.components.schemas.SubmitReportRequest.properties.beforePhotoIds.minItems").value(3))
            .andReturn().response.contentAsString
        val parameters = JsonMapper.builder().build().readTree(api).at("/paths/~1api~1v1~1work-statistics/get/parameters")
        val required = parameters.filter { it.path("required").asBoolean() }.map { it.path("name").asString() }
        assertThat(required).containsExactlyInAnyOrder("from", "to")
        mvc.perform(post("/api/v1/dispatch-offers/offer_${offer.offerId}/response").with(authenticated(10, UserType.DRIVER)).header("Idempotency-Key", "unknown").contentType(MediaType.APPLICATION_JSON).content("{\"response\":\"REJECT\",\"duringPhotoIds\":[]}"))
            .andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/service-requests").with(authenticated(1, UserType.ADMIN))).andExpect(status().isOk)
    }

    @Test
    fun `기기별 공급자 성공은 실패로 강등되지 않고 실제 수신과 열람을 추측하지 않는다`() {
        // given
        val request = registered()
        search(listOf(profile(10)))
        val expansion = checkNotNull(tasks.claim())
        tasks.expanded(expansion, listOf(PushRecipient(OperationsActor(ActorRole.DRIVER, 10), "device-a"), PushRecipient(OperationsActor(ActorRole.DRIVER, 10), "device-b")))
        val first = checkNotNull(tasks.claim())
        val second = checkNotNull(tasks.claim())
        // when
        tasks.delivered(first, true)
        tasks.delivered(second, false, false)
        // then
        val offer = dispatch.offers(checkNotNull(requests.get(request).currentRoundId)).single()
        assertThat(offer.deliveryStatus).isEqualTo(DeliveryStatus.PROVIDER_ACCEPTED)
        assertThat(offer.receivedAt).isNull()
        assertThat(offer.viewedAt).isNull()
        assertThat(requests.get(request).status).isEqualTo(RequestStatus.OPEN)
        transactions.signal(OperationsActor(ActorRole.DRIVER, 10), offer.offerId, false)
        assertThat(dispatch.offer(offer.offerId).receivedAt).isEqualTo(clock.time)
        val history = queries.history(admin, request).rounds.single().offers.single()
        assertThat(history.receivedAt).isEqualTo(clock.time)
        assertThat(history.viewedAt).isNull()
    }

    @Test
    fun `제안 전송은 기기별 세 번만 시도하고 모두 실패하면 후속 알림 의도를 한 번 남긴다`() {
        // given
        val request = registered()
        search(listOf(profile(10)))
        tasks.expanded(checkNotNull(tasks.claim()), listOf(PushRecipient(OperationsActor(ActorRole.DRIVER, 10), "device-a")))
        // when
        repeat(3) { attempt ->
            val send = checkNotNull(tasks.claim())
            assertThat(send.attempts).isEqualTo(attempt + 1)
            tasks.delivered(send, false, true)
            if (attempt < 2) {
                assertThat(tasks.claim()).isNull()
                clock.time = clock.time.plusSeconds(if (attempt == 0) 5 else 10)
            }
        }
        // then
        assertThat(requests.get(request).followUpReason).isEqualTo("DELIVERY_FAILED")
        assertThat(jdbc.queryForObject("select count(*) from operations_tasks where kind='EXPAND_ADMIN'", Long::class.java)).isEqualTo(1)
        assertThat(dispatch.offers(checkNotNull(requests.get(request).currentRoundId)).single().deliveryStatus).isEqualTo(DeliveryStatus.FAILED)
    }

    @Test
    fun `만료한 실행권의 지연 결과는 재시도 회차와 제안을 덮어쓰지 못한다`() {
        // given
        val request = registered()
        val old = checkNotNull(tasks.claim())
        clock.time = clock.time.plusSeconds(60)
        tasks.recover(old.id)
        clock.time = clock.time.plusSeconds(5)
        val fresh = checkNotNull(tasks.claim())
        // when
        tasks.searched(old, listOf(profile(20)))
        tasks.searched(fresh, listOf(profile(10)))
        tasks.searched(old, null, false)
        // then
        val round = checkNotNull(requests.get(request).currentRoundId)
        assertThat(dispatch.round(round).status).isEqualTo(RoundStatus.OFFERING)
        assertThat(dispatch.offers(round).map { it.driverId }).containsExactly(10L)
        assertThat(fresh.attempts).isEqualTo(2)
    }

    @Test
    fun `기기 확장 중 중단되어 만료한 제안은 전송 실패와 구분하여 종료한다`() {
        // given
        val request = registered()
        search(listOf(profile(10)))
        val claim = checkNotNull(tasks.claim())
        clock.time = clock.time.plusSeconds(60)
        // when
        tasks.recover(claim.id)
        // then
        assertThat(requests.get(request).followUpReason).isEqualTo("ROUND_CLOSED")
        assertThat(dispatch.offers(checkNotNull(requests.get(request).currentRoundId)).single().status).isEqualTo(OfferStatus.EXPIRED)
    }

    @Test
    fun `독립 작업자 트랜잭션의 실행권은 같은 작업에 중복 발급되지 않는다`() {
        // given
        registered()
        registered()
        // when
        val claims = race(listOf({ tasks.claim() }, { tasks.claim() })).map { checkNotNull(it.getOrThrow()) }
        // then
        assertThat(claims.map { it.id }.toSet()).hasSize(2)
        assertThat(jdbc.queryForObject("select count(*) from operations_tasks where state='RUNNING'", Long::class.java)).isEqualTo(2)
    }

    @Test
    fun `기사 중복 점유 DB 제약은 명령 영수증과 애플리케이션 사전 검사 없이도 유효하다`() {
        // given
        val first = registered()
        search(listOf(profile(10)))
        val second = registered()
        search(listOf(profile(10)))
        val secondOffer = dispatch.offers(checkNotNull(requests.get(second).currentRoundId)).single()
        accept(first, 10)
        // when & then: 테스트 소유 스키마에 직접 삽입하여 DB 방어선을 검증한다.
        assertThrows<org.springframework.dao.DataIntegrityViolationException> {
            jdbc.update("insert into work_orders(request_id,round_id,offer_id,driver_id,status,version,persistence_version,events,assigned_at,updated_at) values (?,?,?,10,'NOT_STARTED',1,0,'[]'::jsonb,current_timestamp,current_timestamp)", second, secondOffer.roundId, secondOffer.offerId)
        }
        assertThat(count("work_orders")).isEqualTo(1)
        assertThat(requests.get(second).status).isEqualTo(RequestStatus.OPEN)
    }

    @Test
    fun `재배차는 취소 기사를 제외하고 인계 읽기와 이전 기사 접근 범위를 지킨다`() {
        // given
        val request = registered()
        search(listOf(profile(10)))
        val oldWork = accept(request, 10)
        transactions.cancel(OperationsActor(ActorRole.DRIVER, 10), oldWork, "cancel", 1, CancellationReason.OTHER, "이동 불가")
        val waiting = requests.get(request)
        transactions.decide(OperationsActor(ActorRole.ADMIN, 2), request, "redispatch", waiting.version, checkNotNull(waiting.followUpTarget), FollowUpChoice.REDISPATCH)
        search(listOf(profile(10), profile(20)))
        // when
        val newWork = accept(request, 20)
        // then
        assertThat(works.get(newWork).previousWorkId).isEqualTo(oldWork)
        assertThat(dispatch.offers(checkNotNull(requests.get(request).currentRoundId)).map { it.driverId }).containsExactly(20L)
        assertThrows<BusinessException> { queries.work(OperationsActor(ActorRole.DRIVER, 10), newWork) }
        assertThat(queries.work(OperationsActor(ActorRole.DRIVER, 20), oldWork).contactPhone).isNull()
        assertThat(queries.work(OperationsActor(ActorRole.DRIVER, 10), oldWork).contactPhone).isNull()
        assertThat(queries.work(OperationsActor(ActorRole.DRIVER, 20), newWork).contactPhone).isEqualTo("01012345678")
        val history = queries.history(admin, request)
        assertThat(history.rounds).hasSize(2)
        assertThat(history.rounds.first().status).isEqualTo(RoundStatus.ASSIGNED)
        assertThat(history.rounds.first().work?.cancellation?.reasonDetail).isEqualTo("이동 불가")
        assertThat(history.followUpDecisions.single().adminId).isEqualTo("adm_2")
    }

    @Test
    fun `거절은 다른 기사의 응답 창을 닫지 않으며 모두 거절한 회차만 종료한다`() {
        // given
        val request = registered()
        search(listOf(profile(10), profile(20)))
        val offers = dispatch.offers(checkNotNull(requests.get(request).currentRoundId))
        // when
        transactions.respond(OperationsActor(ActorRole.DRIVER, 10), offers.first().offerId, "reject-a", false)
        assertThat(requests.get(request).status).isEqualTo(RequestStatus.OPEN)
        transactions.respond(OperationsActor(ActorRole.DRIVER, 20), offers.last().offerId, "reject-b", false)
        // then
        assertThat(requests.get(request).followUpReason).isEqualTo("NO_ACCEPT")
        assertThat(count("work_orders")).isZero()
    }

    @Test
    fun `커밋된 취소 알림은 모든 관리자의 현재 설치에만 실제 전송하고 정상 진행 알림은 보내지 않는다`() {
        // given: FCM 호출은 루프백 테스트 서버로만 전송한다.
        val driver = OperationsActor(ActorRole.DRIVER, 10)
        val administratorTwo = OperationsActor(ActorRole.ADMIN, 2)
        pushFixture.bind(driver, "driver-device", "test-driver-token")
        pushFixture.bind(admin, "admin-one", "test-admin-one")
        pushFixture.bind(administratorTwo, "admin-two-a", "test-admin-two-a")
        pushFixture.bind(administratorTwo, "admin-two-b", "test-admin-two-b")
        val request = registered()
        worker.tick()
        assertThat(pushFixture.messages).hasSize(1)
        assertThat(pushFixture.messages.single().second["eventType"]).isEqualTo("DISPATCH_OFFER")
        val work = accept(request, 10)
        assertThat(pushFixture.messages).hasSize(1)
        // when
        transactions.cancel(driver, work, "cancel", 1, CancellationReason.CUSTOMER_UNREACHABLE, "연락이 닿지 않음")
        transactions.cancel(driver, work, "cancel", 1, CancellationReason.CUSTOMER_UNREACHABLE, "연락이 닿지 않음")
        worker.tick()
        // then
        val adminSends = pushFixture.messages.filter { it.second["eventType"] == "FOLLOW_UP_REQUIRED" }
        assertThat(adminSends.map { it.first }).containsExactlyInAnyOrder("test-admin-one", "test-admin-two-a", "test-admin-two-b")
        assertThat(adminSends).allSatisfy { assertThat(it.second.keys).containsExactlyInAnyOrder("eventType", "requestId") }
        assertThat(jdbc.queryForObject("select count(*) from operations_tasks where kind='EXPAND_ADMIN'", Long::class.java)).isEqualTo(1)
        worker.tick()
        assertThat(pushFixture.messages).hasSize(4)
    }

    @Test
    fun `해결한 후속 대상의 늦은 알림은 현재 상태와 설치 권한을 다시 확인하여 전송하지 않는다`() {
        // given
        val request = registered()
        search(listOf(profile(10)))
        val work = accept(request, 10)
        transactions.cancel(OperationsActor(ActorRole.DRIVER, 10), work, "cancel", 1, CancellationReason.OTHER, "재일정")
        pushFixture.bind(admin, "admin-one", "test-admin-one")
        val waiting = requests.get(request)
        transactions.decide(admin, request, "cancel-request", waiting.version, checkNotNull(waiting.followUpTarget), FollowUpChoice.CANCEL_REQUEST)
        // when
        worker.tick()
        // then
        assertThat(pushFixture.messages).isEmpty()
        assertThat(requests.get(request).status).isEqualTo(RequestStatus.CANCELLED)
    }

    @Test
    fun `보고서 HTTP 금액은 문자열이나 실수를 변환하지 않고 정수 원 단위만 받는다`() {
        // given
        val request = registered()
        search(listOf(profile(10)))
        val work = accept(request, 10)
        val driver = OperationsActor(ActorRole.DRIVER, 10)
        progress(work, driver)
        val before = uploaded(work, driver, PhotoPhase.BEFORE).joinToString(",") { "\"photo_$it\"" }
        val after = uploaded(work, driver, PhotoPhase.AFTER).joinToString(",") { "\"photo_$it\"" }
        fun submit(cost: String) = mvc.perform(
            post("/api/v1/works/work_$work/reports").with(authenticated(10, UserType.DRIVER)).header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":4,\"workDescription\":\"작업\",\"diagnosis\":\"진단\",\"totalCost\":$cost,\"beforePhotoIds\":[$before],\"afterPhotoIds\":[$after]}"),
        )
        // when & then
        submit("1.5").andExpect(status().isBadRequest)
        submit("\"1000\"").andExpect(status().isBadRequest)
        assertThat(count("work_reports")).isZero()
        submit("1000").andExpect(status().isCreated)
        assertThat(works.get(work).status).isEqualTo(WorkStatus.WORK_DONE)
    }

    @Test
    fun `SGIS 계약은 단일 건물 일치만 채택하고 UTMK를 WGS84로 변환하며 토큰을 재사용한다`() {
        // given: 공식 응답 형식의 가짜 주소/자격증명만 루프백으로 전달한다.
        var authorizations = 0
        var total = "1"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/auth/authentication.json") { exchange ->
            authorizations++
            val body = "{\"errCd\":0,\"result\":{\"accessToken\":\"test-sgis-token\",\"accessTimeout\":\"${clock.time.plusSeconds(3600).epochSecond}\"}}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/addr/geocode.json") { exchange ->
            val body = "{\"errCd\":0,\"result\":{\"totalcount\":\"$total\",\"matching\":\"1\",\"resultdata\":[{\"addr_type\":\"6\",\"sido_nm\":\"서울특별시\",\"sgg_nm\":\"종로구\",\"leg_nm\":\"종로1가\",\"x\":\"953907\",\"y\":\"1952441\"}]}}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val beans = StaticListableBeanFactory()
            beans.addBean("connection", SgisConnection(RestClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").build(), "test-service-id", "test-service-secret"))
            val geocoder = SgisGeocoder(beans.getBeanProvider(SgisConnection::class.java), spatial, clock)
            // when
            val site = geocoder.resolve("가상 현장 주소")
            geocoder.resolve("가상 현장 주소")
            total = "2"
            val ambiguous = assertThrows<BusinessException> { geocoder.resolve("모호한 가상 주소") }
            // then
            assertThat(authorizations).isEqualTo(1)
            assertThat(site.longitude).isBetween(126.0, 128.0)
            assertThat(site.latitude).isBetween(37.0, 38.0)
            assertThat(site.area).isEqualTo("서울특별시 종로구 종로1가")
            assertThat(ambiguous.errorCode).isEqualTo(com.allcheck.operations.exception.OperationsErrorCode.SITE_NOT_RESOLVED)
        } finally {
            server.stop(0)
        }
    }

    private fun authenticated(accountId: Long, userType: UserType, authority: UserType = userType) = authentication(
        UsernamePasswordAuthenticationToken.authenticated(
            AuthPrincipal(accountId, 1L, userType, AdminRole.SUB.takeIf { userType == UserType.ADMIN }),
            null,
            AuthorityUtils.createAuthorityList("ROLE_${authority.name}"),
        ),
    )

    private fun metadata(phase: PhotoPhase) = PhotoMetadata(phase, "site.jpg", "image/jpeg", 100, java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))
    private fun uploaded(workId: Long, actor: OperationsActor, phase: PhotoPhase): List<Long> = (1..3).map {
        val grant = photos.reserve(actor, workId, key(), metadata(phase)).first
        val id = grant.photoFileId.removePrefix("photo_").toLong()
        photos.complete(actor, workId, id, key())
        id
    }

    private fun confirmed(): Pair<Long, Int> {
        val reception = receptions.create(1, ReceptionCreationData())
        val id = ReceptionExternalId.RECEPTION.parse(reception.receptionId)
        val from = clock.time.plusSeconds(3600).atOffset(ZoneOffset.ofHours(9))
        val confirmed = receptions.confirm(1, id, reception.version, ReceptionConfirmationData(com.allcheck.reception.domain.vo.review.WorkType.BOILER, "온수 이상", "서울특별시 중구 가상로 12 101호", from, from.plusHours(1), "01012345678"))
        return id to confirmed.version
    }
    private fun registered(): Long {
        val (id, version) = confirmed()
        return service.register(admin, id, version, key()).value.requestId
    }
    private fun search(drivers: List<DriverCandidate>) {
        var claim = checkNotNull(tasks.claim())
        while (claim.kind != TaskKind.SEARCH) {
            // 다른 의뢰의 전송 준비 작업은 테스트 범위 밖이다. 공급자 전송은 하지 않는다.
            tasks.expanded(claim, listOf(PushRecipient(OperationsActor(ActorRole.DRIVER, dispatch.offer(claim.subjectId).driverId), "test-device")))
            claim = checkNotNull(tasks.claim())
        }
        tasks.searched(claim, drivers)
    }
    private fun accept(requestId: Long, driverId: Long): Long {
        val offer = dispatch.offers(checkNotNull(requests.get(requestId).currentRoundId)).single { it.driverId == driverId }
        return transactions.respond(OperationsActor(ActorRole.DRIVER, driverId), offer.offerId, key(), true).value.resourceId
    }
    private fun progress(work: Long, driver: OperationsActor) {
        listOf(WorkStatus.EN_ROUTE, WorkStatus.ARRIVED, WorkStatus.IN_PROGRESS).forEachIndexed { i, status -> transactions.transition(driver, work, key(), i + 1, status, null, null) }
    }
    private fun profile(id: Long) = DriverCandidate(id, 127.0, 37.5, clock.time, Duration.ofMinutes(5))
    private fun count(table: String) = jdbc.queryForObject("select count(*) from $table", Long::class.java)!!
    private fun key() = UUID.randomUUID().toString()
    private fun <T> race(actions: List<() -> T>): List<Result<T>> {
        val ready = CountDownLatch(actions.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(actions.size)
        try {
            val futures = actions.map { action ->
                executor.submit<Result<T>> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    runCatching(action)
                }
            }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            return futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    class MutableClock : Clock() {
        @Volatile var time: Instant = Instant.parse("2026-10-31T14:59:00Z")
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = time
    }
    class GeocoderFixture : OperationsGeocoder {
        var fail = false
        var barrier: java.util.concurrent.CyclicBarrier? = null
        override fun resolve(address: String): SiteCoordinates {
            if (fail) throw BusinessException(com.allcheck.operations.exception.OperationsErrorCode.GEOCODER_UNAVAILABLE)
            barrier?.await(10, TimeUnit.SECONDS)
            return SiteCoordinates(127.0, 37.5, "서울특별시 중구 가상동")
        }
    }
    class PhotoFixture : PhotoStorageOperations {
        var fail = false
        override fun bucket() = "operations-test-photos"
        override fun available() = Unit
        override fun upload(photo: PhotoSnapshot) = SignedObjectUrl("https://storage.invalid/upload", Instant.parse("2026-11-01T00:00:00Z"), emptyMap())
        override fun access(photo: PhotoSnapshot) = SignedObjectUrl("https://storage.invalid/download", Instant.parse("2026-11-01T00:00:00Z"), emptyMap())
        override fun verify(photo: PhotoSnapshot) {
            if (fail) throw BusinessException(com.allcheck.global.exception.GlobalErrorCode.FILE_UPLOAD_NOT_VERIFIED)
        }
    }
    class PushFixture :
        OperationsPushDevices,
        AutoCloseable {
        val bindings = java.util.concurrent.ConcurrentHashMap<OperationsActor, Map<String, PushTarget>>()
        val messages = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        private val mapper = JsonMapper.builder().build()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/projects/operations-test/messages:send") { exchange ->
                val message = mapper.readTree(exchange.requestBody.readAllBytes()).path("message")
                val data = message.path("data").properties().associate { it.key to it.value.asString() }
                messages.add(message.path("token").asString() to data)
                val body = "{\"name\":\"projects/operations-test/messages/test-${messages.size}\"}".toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        fun connection() = FcmConnection("operations-test", RestClient.builder().baseUrl("http://127.0.0.1:${server.address.port}").build(), { "test-oauth" })
        fun bind(actor: OperationsActor, installationId: String, token: String) {
            val target = PushTarget(actor.id, installationId, token, tokenHash = token, bindingVersion = 1)
            bindings.compute(actor) { _, entries -> entries.orEmpty() + (installationId to target) }
        }
        override fun installations(actor: OperationsActor) = bindings[actor]?.keys?.toList().orEmpty()
        override fun administrators() = listOf(1L, 2L)
        override fun current(actor: OperationsActor, installationId: String) = bindings[actor]?.get(installationId)
        override fun invalidateIfCurrent(actor: OperationsActor, target: PushTarget) {
            bindings.computeIfPresent(actor) { _, entries ->
                if (entries[target.installationId]?.sameBinding(target) == true) entries - target.installationId else entries
            }
        }
        override fun close() {
            server.stop(0)
        }
    }

    @TestConfiguration
    class Fixtures {
        @Bean @Primary
        fun operationsTestClock() = MutableClock()

        @Bean @Primary
        fun geocoder() = GeocoderFixture()

        @Bean @Primary
        fun photoFixture() = PhotoFixture()

        @Bean(destroyMethod = "close")
        @Primary
        fun pushFixture() = PushFixture()

        @Bean fun operationsFcmConnection(fixture: PushFixture) = fixture.connection()

        @Bean @Primary
        fun operationsDrivers(clock: MutableClock) = object : OperationsDrivers {
            override fun candidates(context: CandidateContext) = listOf(current(10), current(20))
            private fun current(driverId: Long) = DriverCandidate(driverId, 127.0, 37.5, clock.time, Duration.ofMinutes(5))
            override fun lockForAcceptance(driverId: Long, context: CandidateContext) = current(driverId)
        }
    }
    companion object {
        private val schema = PostgresTestSchema("operations_test")

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = schema.register(registry)
    }
}
