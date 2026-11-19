package com.allcheck.operations

import com.allcheck.global.exception.BusinessException
import com.allcheck.operations.coordination.application.OperationsService
import com.allcheck.operations.coordination.application.OperationsTaskTransactions
import com.allcheck.operations.coordination.application.OperationsTransactions
import com.allcheck.operations.coordination.application.PushRecipient
import com.allcheck.operations.dispatch.DispatchOperations
import com.allcheck.operations.domain.entity.TaskKind
import com.allcheck.operations.request.RequestOperations
import com.allcheck.operations.work.WorkOperations
import com.allcheck.reception.application.review.ReceptionService
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.domain.vo.review.ReceptionConfirmationData
import com.allcheck.reception.domain.vo.review.ReceptionCreationData
import com.allcheck.support.PostgresTestSchema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest(properties = ["spring.jpa.show-sql=false"])
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
    private val clock: MutableClock,
    private val geo: GeocoderFixture,
    private val dataSource: DataSource,
) {
    private val admin = OperationsActor(ActorRole.ADMIN, 1)
    private val jdbc get() = JdbcTemplate(dataSource)

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE receptions,operations_receipts RESTART IDENTITY CASCADE")
        clock.time = Instant.parse("2026-10-31T14:59:00Z")
        geo.fail = false
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
    fun `서로 다른 기사의 동시 수락은 한 작업과 한 수락만 커밋한다`() {
        // given
        val request = registered()
        search(listOf(profile(10), profile(20)))
        val offers = dispatch.offers(checkNotNull(requests.get(request).currentRoundId))
        // when
        val results = race(offers.map { offer -> { transactions.respond(OperationsActor(ActorRole.DRIVER, offer.driverId), offer.offerId, key(), true, profile(offer.driverId)) } })
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
        val results = race(offers.map { offer -> { transactions.respond(OperationsActor(ActorRole.DRIVER, 10), offer.offerId, key(), true, profile(10)) } })
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
        assertThrows<BusinessException> { transactions.respond(OperationsActor(ActorRole.DRIVER, 10), offer.offerId, key(), true, profile(10)) }
        tasks.expireRequest(requestId)
        // then
        assertThat(requests.get(requestId).followUpReason).isEqualTo("ROUND_CLOSED")
        assertThat(count("work_orders")).isZero()
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
    private fun search(drivers: List<DriverAvailability>) {
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
        return transactions.respond(OperationsActor(ActorRole.DRIVER, driverId), offer.offerId, key(), true, profile(driverId)).value.resourceId
    }
    private fun progress(work: Long, driver: OperationsActor) {
        listOf(WorkStatus.EN_ROUTE, WorkStatus.ARRIVED, WorkStatus.IN_PROGRESS).forEachIndexed { i, status -> transactions.transition(driver, work, key(), i + 1, status, null, null) }
    }
    private fun profile(id: Long) = DriverAvailability(id, true, true, setOf(WorkType.BOILER), true, 127.0, 37.5, clock.time)
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
        override fun resolve(address: String): SiteCoordinates {
            if (fail) throw BusinessException(com.allcheck.operations.exception.OperationsErrorCode.GEOCODER_UNAVAILABLE)
            return SiteCoordinates(127.0, 37.5, "서울특별시 중구 가상동")
        }
    }

    @TestConfiguration
    class Fixtures {
        @Bean @Primary
        fun operationsTestClock() = MutableClock()

        @Bean @Primary
        fun geocoder() = GeocoderFixture()
    }
    companion object {
        private val schema = PostgresTestSchema("operations_test")

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = schema.register(registry)
    }
}
