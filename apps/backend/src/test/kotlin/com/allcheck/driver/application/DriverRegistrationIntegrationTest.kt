package com.allcheck.driver.application

import com.allcheck.driver.application.notification.DriverPushData
import com.allcheck.driver.application.notification.DriverPushResult
import com.allcheck.driver.application.notification.DriverPushSender
import com.allcheck.driver.application.notification.DriverPushTransactions
import com.allcheck.driver.application.notification.DriverPushWorker
import com.allcheck.driver.application.registration.DriverRegistrationTransactions
import com.allcheck.driver.application.registration.identity
import com.allcheck.driver.domain.vo.registration.BusinessIdentity
import com.allcheck.driver.domain.vo.registration.BusinessOperatingStatus
import com.allcheck.driver.domain.vo.registration.VerifiedBusinessIdentity
import com.allcheck.driver.domain.vo.registration.WorkType
import com.allcheck.driver.domain.vo.review.ApprovalDecision
import com.allcheck.driver.exception.DriverErrorCode
import com.allcheck.driver.infrastructure.registration.NtsBusinessClient
import com.allcheck.driver.infrastructure.repository.registration.DriverRepository
import com.allcheck.driver.presentation.dto.request.registration.BusinessInfoRequest
import com.allcheck.driver.presentation.dto.request.registration.BusinessVerificationRequest
import com.allcheck.driver.presentation.dto.request.registration.DriverProfileRequest
import com.allcheck.driver.presentation.dto.request.registration.DriverRegistrationRequest
import com.allcheck.driver.presentation.dto.request.registration.DriverResubmissionRequest
import com.allcheck.driver.presentation.dto.request.registration.SettlementAccountRequest
import com.allcheck.driver.presentation.dto.request.review.DriverApprovalRequest
import com.allcheck.driver.presentation.dto.request.withdrawal.DriverWithdrawalRequest
import com.allcheck.driver.presentation.dto.response.registration.DriverRegistrationResult
import com.allcheck.driver.presentation.registration.toCommand
import com.allcheck.global.exception.BusinessException
import com.allcheck.global.security.jwt.JwtTokenProvider
import com.allcheck.identity.AdminRole
import com.allcheck.identity.PushDeviceOperations
import com.allcheck.identity.PushTarget
import com.allcheck.identity.application.login.KakaoSessionService
import com.allcheck.identity.domain.entity.account.Account
import com.allcheck.identity.domain.entity.session.AuthSession
import com.allcheck.identity.domain.vo.account.AdminCredential
import com.allcheck.identity.domain.vo.account.SocialLogin
import com.allcheck.identity.infrastructure.repository.account.AccountRepository
import com.allcheck.identity.infrastructure.repository.session.AuthSessionRepository
import com.allcheck.identity.infrastructure.session.RefreshTokenProvider
import com.allcheck.operations.ActorRole
import com.allcheck.operations.OperationsActor
import com.allcheck.operations.OperationsGeocoder
import com.allcheck.operations.OperationsPushDevices
import com.allcheck.operations.RequestIntake
import com.allcheck.operations.SiteCoordinates
import com.allcheck.operations.WorkStatus
import com.allcheck.operations.coordination.application.OperationsTaskTransactions
import com.allcheck.operations.coordination.application.OperationsWorker
import com.allcheck.operations.dispatch.DispatchOperations
import com.allcheck.operations.dispatch.OfferSnapshot
import com.allcheck.operations.domain.entity.TaskKind
import com.allcheck.operations.request.RequestOperations
import com.allcheck.operations.work.WorkOperations
import com.allcheck.reception.application.review.ReceptionService
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.domain.vo.review.ReceptionCreationData
import com.allcheck.support.PostgresTestSchema
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

@SpringBootTest(
    properties = [
        "spring.datasource.url=\${IDENTITY_TEST_DATABASE_URL:jdbc:postgresql://localhost:15432/allcheck_identity_test}",
        "spring.datasource.username=\${IDENTITY_TEST_DATABASE_USER:allcheck}",
        "spring.datasource.password=\${IDENTITY_TEST_DATABASE_PASSWORD:allcheck_local_6}",
        "spring.jpa.show-sql=false",
        "springdoc.api-docs.enabled=true",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource("classpath:jwt-test.properties")
class DriverRegistrationIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val mapper: JsonMapper,
    private val login: KakaoSessionService,
    private val accounts: AccountRepository,
    private val sessions: AuthSessionRepository,
    private val tokens: JwtTokenProvider,
    private val refreshTokens: RefreshTokenProvider,
    private val drivers: DriverRepository,
    private val tasks: OperationsTaskTransactions,
    private val operationsWorker: OperationsWorker,
    private val operationsDevices: OperationsPushDevices,
    private val receptions: ReceptionService,
    private val requests: RequestOperations,
    private val dispatch: DispatchOperations,
    private val jdbc: JdbcTemplate,
    private val dataSource: DataSource,
    private val pushWorker: DriverPushWorker,
    private val pushTransactions: DriverPushTransactions,
    private val pushDevices: PushDeviceOperations,
    private val workOperations: WorkOperations,
    private val commandService: DriverRegistrationTransactions,
    transactionManager: PlatformTransactionManager,
) {
    @MockitoBean private lateinit var nts: NtsBusinessClient

    @MockitoBean private lateinit var geocoder: OperationsGeocoder

    @MockitoBean private lateinit var fcm: DriverPushSender
    private val tx = TransactionTemplate(transactionManager)
    private val ownAccounts = mutableListOf<Long>()
    private val registrationPath = "/api/v1/drivers/me/registration"

    @AfterAll
    fun removeTestSchema() {
        database.remove(dataSource)
    }

    @BeforeEach
    fun verifyBusinessOutsideTransaction() {
        Mockito.`when`(nts.verify(anyIdentity())).thenAnswer {
            check(!TransactionSynchronizationManager.isActualTransactionActive())
            VerifiedBusinessIdentity(it.getArgument(0), BusinessOperatingStatus.CONTINUING, Instant.now())
        }
    }

    @AfterEach
    fun cleanOnlyOwnFixtures() {
        tx.executeWithoutResult {
            ownAccounts.mapNotNull { drivers.findByAccountId(it) }.forEach { driver ->
                val businessId = checkNotNull(driver.business.id)
                val driverId = checkNotNull(driver.id)
                jdbc.update("update service_requests set current_work_id=null where current_work_id in (select work_id from work_orders where driver_id=?)", driverId)
                jdbc.update("delete from work_orders where driver_id = ?", driverId)
                jdbc.update("delete from driver_dispatch_states where driver_id = ?", driverId)
                jdbc.update("delete from driver_push_deliveries where event_id in (select event_id from driver_notification_events where driver_id = ?)", checkNotNull(driver.id))
                jdbc.update("delete from driver_notification_events where driver_id = ?", checkNotNull(driver.id))
                jdbc.update("delete from driver_registration_history where driver_id = ?", checkNotNull(driver.id))
                listOf("driver_profile_specialties", "driver_profile_regions", "driver_profile_equipment").forEach { table -> jdbc.update("delete from $table where driver_id = ?", checkNotNull(driver.id)) }
                jdbc.update("delete from drivers where driver_id = ?", checkNotNull(driver.id))
                jdbc.update("delete from driver_businesses where business_id = ?", businessId)
            }
            ownAccounts.forEach { accountId ->
                jdbc.update("delete from request_limit_buckets where bucket_id = ?", "driver-registration:$accountId")
                jdbc.update("delete from driver_idempotency_records where actor_id = ?", accountId)
                jdbc.update("delete from identity_push_devices where account_id = ?", accountId)
                jdbc.update("delete from identity_auth_sessions where account_id = ?", accountId)
                jdbc.update("delete from identity_accounts where account_id = ?", accountId)
            }
        }
        ownAccounts.clear()
    }

    @Test
    fun `미가입은 404이며 가입과 동일 키 재시도는 계정과 신청을 중복 생성하지 않는다`() {
        // given
        val actor = driver()
        mvc.perform(get(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header)).andExpect(status().isNotFound)
        val request = request()

        // when
        val first = submit(actor, request)
        mvc.perform(
            post(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "submit")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)),
        )
            .andExpect(status().isCreated).andExpect(header().string("Idempotency-Replayed", "true"))
            .andExpect(jsonPath("$.driverId").value(first.driverId))

        // then
        assertThat(first.version).isEqualTo(1)
        mvc.perform(get(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header))
            .andExpect(status().isOk).andExpect(jsonPath("$.accountStatus").value("PENDING_APPROVAL"))
            .andExpect(jsonPath("$.profile.settlementAccount.accountNumber").value("123456789012"))
        assertThat(jdbc.queryForObject("select count(*) from drivers where account_id = ?", Long::class.java, actor.accountId)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from driver_notification_events where driver_id = ?", Long::class.java, first.driverId)).isEqualTo(1)
        Mockito.verify(nts, Mockito.times(1)).verify(anyIdentity())
    }

    @ParameterizedTest
    @EnumSource(WorkType::class)
    fun `다섯 작업 분야 모두 가입 후 조회할 수 있다`(workType: WorkType) {
        // given
        val actor = driver()
        val original = request()
        val registration = original.copy(profile = original.profile.copy(specialties = listOf(workType)))

        // when
        submit(actor, registration)

        // then
        mvc.perform(get(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profile.specialties[0]").value(workType.name))
        assertThat(tx.execute { checkNotNull(drivers.findByAccountId(actor.accountId)).profile.specialties.toList() }).containsExactly(workType)
    }

    @ParameterizedTest
    @ValueSource(strings = ["******", "********9012", "------", "123*456", "-123456", "123456-"])
    fun `마스킹되거나 숫자 그룹이 아닌 계좌번호는 외부 검증과 저장 전에 거부한다`(accountNumber: String) {
        // given
        val actor = driver()
        val original = request()
        val registration = original.copy(
            profile = original.profile.copy(
                settlementAccount = original.profile.settlementAccount.copy(accountNumber = accountNumber),
            ),
        )

        // when
        val result = mvc.perform(
            post(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header)
                .header("Idempotency-Key", "invalid-account").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(registration)),
        )

        // then
        result.andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
        assertThat(drivers.existsByAccountId(actor.accountId)).isFalse()
        Mockito.verifyNoInteractions(nts)
    }

    @Test
    fun `숫자 그룹으로 구성된 계좌번호는 원본을 저장하고 관리자 응답만 마스킹한다`() {
        // given
        val actor = driver()
        val reviewer = admin()
        val original = request()
        val registration = original.copy(
            profile = original.profile.copy(
                settlementAccount = original.profile.settlementAccount.copy(accountNumber = "123-456-789012"),
            ),
        )

        // when
        val registered = submit(actor, registration)

        // then
        mvc.perform(get(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profile.settlementAccount.accountNumber").value("123-456-789012"))
        mvc.perform(get("/api/v1/drivers/${registered.driverId}").header(HttpHeaders.AUTHORIZATION, reviewer.header))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profile.settlementAccount.accountNumber").value("**********9012"))
        assertThat(tx.execute { checkNotNull(drivers.findByAccountId(actor.accountId)).profile.accountNumber }).isEqualTo("123-456-789012")
    }

    @ParameterizedTest
    @EnumSource(AdminRole::class)
    fun `두 관리자 역할은 신청을 심사하고 부분 검색하며 기사 개인정보는 다른 기사에게 공개하지 않는다`(role: AdminRole) {
        // given
        val actor = driver()
        val admin = admin(role)
        val registered = submit(actor, request())

        // when & then
        mvc.perform(get("/api/v1/drivers").param("q", "길동").header(HttpHeaders.AUTHORIZATION, admin.header))
            .andExpect(status().isOk).andExpect(jsonPath("$.items[0].driverId").value(registered.driverId))
        mvc.perform(get("/api/v1/drivers/${registered.driverId}").header(HttpHeaders.AUTHORIZATION, admin.header))
            .andExpect(status().isOk).andExpect(jsonPath("$.profile.settlementAccount.accountNumber").value("********9012"))
        mvc.perform(get("/api/v1/drivers/${registered.driverId}").header(HttpHeaders.AUTHORIZATION, actor.header)).andExpect(status().isForbidden)
        mvc.perform(
            post("/api/v1/drivers/${registered.driverId}/approval").header(HttpHeaders.AUTHORIZATION, admin.header)
                .header("Idempotency-Key", "approve").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(DriverApprovalRequest(1, ApprovalDecision.APPROVE))),
        )
            .andExpect(status().isOk).andExpect(jsonPath("$.accountStatus").value("ACTIVATED")).andExpect(jsonPath("$.version").value(2))
    }

    @Test
    fun `반려 후 같은 기사로 재신청하고 이전 신청과 반려 이력을 보존한다`() {
        // given
        val actor = driver()
        val admin = admin()
        val registered = submit(actor, request())
        decide(admin, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.REJECT, "서류 확인 필요"))
        val revised = request("수정기사").let {
            it.copy(profile = it.profile.copy(specialties = listOf(WorkType.BOILER, WorkType.WATERPROOFING), activityRegions = listOf("부산"), equipment = emptyList()))
        }

        // when
        mvc.perform(
            post("$registrationPath/resubmission").header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "resubmit")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(DriverResubmissionRequest(2, revised))),
        )
            .andExpect(status().isOk).andExpect(jsonPath("$.driverId").value(registered.driverId)).andExpect(jsonPath("$.version").value(3))

        // then
        mvc.perform(get("/api/v1/drivers/${registered.driverId}").header(HttpHeaders.AUTHORIZATION, admin.header))
            .andExpect(status().isOk).andExpect(jsonPath("$.rejectionHistory[0].reason").value("서류 확인 필요"))
            .andExpect(jsonPath("$.profile.workerName").value("수정기사"))
            .andExpect(jsonPath("$.profile.specialties[0]").value("BOILER"))
            .andExpect(jsonPath("$.profile.specialties[1]").value("WATERPROOFING"))
            .andExpect(jsonPath("$.profile.activityRegions[0]").value("부산"))
            .andExpect(jsonPath("$.profile.equipment").isEmpty)
        assertThat(jdbc.queryForObject("select count(*) from driver_registration_history where driver_id = ? and event_type = 'SUBMITTED'", Long::class.java, registered.driverId)).isEqualTo(2)
    }

    @ParameterizedTest
    @EnumSource(value = BusinessOperatingStatus::class, names = ["SUSPENDED", "CLOSED"])
    fun `진위가 일치해도 휴업 폐업 사업자는 가입 신청을 저장하지 않는다`(operatingStatus: BusinessOperatingStatus) {
        // given
        val actor = driver()
        Mockito.`when`(nts.verify(anyIdentity())).thenAnswer { VerifiedBusinessIdentity(it.getArgument(0), operatingStatus, Instant.now()) }

        // when & then
        mvc.perform(
            post(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "submit")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request())),
        )
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("BUSINESS_INFO_INVALID"))
        assertThat(drivers.existsByAccountId(actor.accountId)).isFalse()
    }

    @ParameterizedTest
    @EnumSource(WorkStatus::class)
    fun `실제 작업 상태를 조회해 미완료 작업이 있으면 탈퇴를 막고 완료 취소 작업만 있으면 탈퇴한다`(workStatus: WorkStatus) {
        // given
        val actor = driver()
        val admin = admin()
        val registered = submit(actor, request())
        decide(admin, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.APPROVE))
        val offer = offerFor(admin.accountId, registered.driverId)
        val work = tx.execute {
            workOperations.create(offer.requestId, offer.roundId, offer.offerId, offer.driverId, null, Instant.now())
        }
        val completed = if (workStatus == WorkStatus.WORK_DONE) Timestamp.from(Instant.now()) else null
        val cancelled = if (workStatus == WorkStatus.CANCELLED) Timestamp.from(Instant.now()) else null
        jdbc.update("update work_orders set status = ?, completed_at = ?, cancelled_at = ? where work_id = ?", workStatus.name, completed, cancelled, work.workId)
        val terminal = workStatus in listOf(WorkStatus.WORK_DONE, WorkStatus.CANCELLED)

        // when & then
        mvc.perform(
            post("/api/v1/drivers/me/withdrawal").header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "withdraw")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(DriverWithdrawalRequest(2, true))),
        )
            .andExpect(status().`is`(if (terminal) 200 else 409))
        assertThat(accounts.findById(actor.accountId).orElseThrow().enabled).isEqualTo(!terminal)
        mvc.perform(get("/api/v1/auth/accounts/me").header(HttpHeaders.AUTHORIZATION, actor.header))
            .andExpect(status().`is`(if (terminal) 401 else 200))
    }

    @Test
    fun `멱등성 키에 다른 본문과 오래된 버전을 사용하면 상태를 바꾸지 않는다`() {
        // given
        val actor = driver()
        val admin = admin()
        val registered = submit(actor, request())

        // when & then
        mvc.perform(
            post(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "submit")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request("다른이름"))),
        )
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
        mvc.perform(
            post("/api/v1/drivers/${registered.driverId}/approval").header(HttpHeaders.AUTHORIZATION, admin.header)
                .header("Idempotency-Key", "stale").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(DriverApprovalRequest(99, ApprovalDecision.APPROVE))),
        )
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["unknown-field", "blank-region", "duplicate-specialty", "empty-specialty", "null-specialty", "bad-email", "blank-name", "missing-key"])
    fun `가입 입력과 멱등성 키를 검증하고 잘못된 입력으로 국세청을 호출하지 않는다`(scenario: String) {
        // given
        val actor = driver()
        val original = request()
        val request = when (scenario) {
            "blank-region" -> original.copy(profile = original.profile.copy(activityRegions = listOf(" ")))
            "duplicate-specialty" -> original.copy(profile = original.profile.copy(specialties = listOf(WorkType.BLOCKAGE, WorkType.BLOCKAGE)))
            "empty-specialty" -> original.copy(profile = original.profile.copy(specialties = emptyList()))
            "bad-email" -> original.copy(profile = original.profile.copy(email = "invalid-email"))
            "blank-name" -> original.copy(profile = original.profile.copy(workerName = " "))
            else -> original
        }
        val json = mapper.writeValueAsString(request).let {
            when (scenario) {
                "unknown-field" -> it.dropLast(1) + ",\"accountStatus\":\"ACTIVATED\"}"
                "null-specialty" -> it.replace("[\"BLOCKAGE\"]", "[null]")
                else -> it
            }
        }
        val builder = post(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header).contentType(MediaType.APPLICATION_JSON).content(json)
        if (scenario != "missing-key") builder.header("Idempotency-Key", "submit")

        // when & then
        mvc.perform(builder).andExpect(status().isBadRequest)
        Mockito.verifyNoInteractions(nts)
        assertThat(drivers.existsByAccountId(actor.accountId)).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["/api/v1/drivers/me/works", "/api/v1/drivers/me/chat/messages", "/api/v1/drivers/me/profile", "/api/v1/drivers/me/dispatch-offers", "/api/v1/works/example/transitions", "/api/v1/dispatch-offers/example", "/api/v1/service-requests/example", "/api/v1/reports/example"])
    fun `미가입 승인 대기 반려 기사는 실제 현재 상태로 일반 업무 경로를 차단한다`(path: String) {
        // given
        val actor = driver()
        val admin = admin()
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, actor.header)).andExpect(status().isForbidden)
        val registered = submit(actor, request())

        // when & then
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, actor.header)).andExpect(status().isForbidden)
        decide(admin, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.REJECT, "확인 필요"))
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, actor.header)).andExpect(status().isForbidden)
        mvc.perform(get(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header)).andExpect(status().isOk)
    }

    @Test
    fun `진행 중 동일 멱등성 키는 409이고 커밋 후 재시도는 최초 성공을 재현한다`() {
        // given
        val actor = driver()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit {
                tx.executeWithoutResult {
                    val data = request()
                    commandService.submit(actor.accountId, "submit", data.toCommand(), VerifiedBusinessIdentity(data.toCommand().business.identity(), BusinessOperatingStatus.CONTINUING, Instant.now()))
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            check(entered.await(10, TimeUnit.SECONDS))

            // when & then
            mvc.perform(
                post(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "submit")
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request())),
            )
                .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("OPERATION_IN_PROGRESS"))
            release.countDown()
            pending.get(15, TimeUnit.SECONDS)
            mvc.perform(
                post(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "submit")
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request())),
            )
                .andExpect(status().isCreated).andExpect(header().string("Idempotency-Replayed", "true"))
        } finally {
            release.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `동시 최초 가입은 기사 한 명과 제출 이력 한 건만 생성한다`() {
        // given
        val actor = driver()

        // when
        val results = concurrently(
            { submitStatus(actor, "first") },
            { submitStatus(actor, "second") },
        )

        // then
        assertThat(results).containsExactlyInAnyOrder(201, 409)
        val driver = checkNotNull(drivers.findByAccountId(actor.accountId))
        assertThat(jdbc.queryForObject("select count(*) from driver_registration_history where driver_id = ?", Long::class.java, checkNotNull(driver.id))).isEqualTo(1)
    }

    @Test
    fun `새 작업 배정과 탈퇴가 경쟁해도 탈퇴 기사에게 미완료 작업이 생성되지 않는다`() {
        // given
        val actor = driver()
        val admin = admin()
        val registered = submit(actor, request())
        decide(admin, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.APPROVE))

        markReady(actor)
        val offer = offerFor(admin.accountId, registered.driverId)

        // when
        val results = concurrently(
            { mvc.perform(acceptOffer(actor, "offer_${offer.offerId}")).andReturn().response.status },
            {
                mvc.perform(
                    post("/api/v1/drivers/me/withdrawal").header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "withdraw")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(DriverWithdrawalRequest(2, true))),
                ).andReturn().response.status
            },
        )

        // then
        val assigned = results.first() == 200
        if (!assigned) assertThat(results.first()).isIn(401, 403, 409)
        assertThat(results.last()).isEqualTo(if (assigned) 409 else 200)
        assertThat(accounts.findById(actor.accountId).orElseThrow().enabled).isEqualTo(assigned)
        assertThat(workOperations.occupied(registered.driverId)).isEqualTo(assigned)
    }

    @Test
    fun `인증된 접수와 배차는 실제 기사 자격 기기 매핑과 작업 점유로 이어진다`() {
        // given
        val admin = admin()
        val otherAdmin = admin(AdminRole.SUB)
        val actor = driver()
        val otherRegion = driver()
        val profile = request().profile
        val eligible = submit(actor, request().copy(profile = profile.copy(activityRegions = listOf("서울 강남구"))))
        val ineligible = submit(otherRegion, request().copy(profile = profile.copy(activityRegions = listOf("서울 강동구"))))
        assertThat(actor.accountId).isNotEqualTo(eligible.driverId)
        setAvailability(actor, "READY").andExpect(status().isForbidden)
        decide(admin, eligible.driverId, DriverApprovalRequest(1, ApprovalDecision.APPROVE))
        decide(admin, ineligible.driverId, DriverApprovalRequest(1, ApprovalDecision.APPROVE))
        mvc.perform(get("/api/v1/drivers/me/availability").header(HttpHeaders.AUTHORIZATION, actor.header))
            .andExpect(status().isOk).andExpect(jsonPath("$.availability").value("UNAVAILABLE"))
        markReady(actor)
        markReady(otherRegion)
        registerDevice(actor, "ack31-eligible", "eligible-fcm-token")

        val reception = mvc.perform(
            post("/api/v1/receptions").header(HttpHeaders.AUTHORIZATION, admin.header).header("Idempotency-Key", "reception")
                .contentType(MediaType.APPLICATION_JSON).content("{}"),
        ).andExpect(status().isCreated).andReturn().response.contentAsString.let(mapper::readTree)
        val receptionId = reception["receptionId"].asString()
        val address = "서울특별시 강남구 가상로 12"
        Mockito.`when`(geocoder.resolve(address)).thenReturn(SiteCoordinates(127.0, 37.5, "서울특별시 강남구 역삼동"))
        val from = Instant.now().atOffset(ZoneOffset.UTC).plusHours(1)
        val confirmation = mapOf(
            "expectedVersion" to reception["version"].asInt(),
            "finalData" to mapOf(
                "workType" to "BLOCKAGE",
                "symptoms" to "배관 막힘",
                "address" to address,
                "contactPhone" to "01012345678",
                "desiredVisitFrom" to from.toString(),
                "desiredVisitTo" to from.plusHours(1).toString(),
            ),
        )
        val confirmed = mvc.perform(
            post("/api/v1/receptions/$receptionId/confirmation").header(HttpHeaders.AUTHORIZATION, admin.header).header("Idempotency-Key", "confirm")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(confirmation)),
        ).andExpect(status().isOk).andReturn().response.contentAsString.let(mapper::readTree)

        // when
        val registration = mvc.perform(
            post("/api/v1/service-requests").header(HttpHeaders.AUTHORIZATION, admin.header).header("Idempotency-Key", "request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("receptionId" to receptionId, "expectedVersion" to confirmed["version"].asInt()))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString.let(mapper::readTree)
        val requestId = registration["requestId"].asString()
        val search = checkNotNull(tasks.claim())
        assertThat(search.kind).isEqualTo(TaskKind.SEARCH)
        operationsWorker.execute(search)
        val expansion = checkNotNull(tasks.claim())
        assertThat(expansion.kind).isEqualTo(TaskKind.EXPAND_OFFER)
        operationsWorker.execute(expansion)
        val delivery = checkNotNull(tasks.claim())
        assertThat(delivery.kind).isEqualTo(TaskKind.PUSH_OFFER)
        assertThat(delivery.actor).isEqualTo(OperationsActor(ActorRole.DRIVER, eligible.driverId))
        assertThat(delivery.installation).isEqualTo("ack31-eligible")
        val offerId = "offer_${delivery.subjectId}"

        // then
        mvc.perform(get("/api/v1/dispatch-offers/$offerId").header(HttpHeaders.AUTHORIZATION, otherRegion.header)).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/dispatch-offers/$offerId").header(HttpHeaders.AUTHORIZATION, actor.header))
            .andExpect(status().isOk).andExpect(jsonPath("$.addressArea").value("서울특별시 강남구 역삼동"))
        val issueAt = Instant.now()
        sendLocation(actor, """{"issue":"PERMISSION_DENIED","occurredAt":"$issueAt"}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.locationUsability").value("MISSING"))
        sendLocation(actor, """{"latitude":37.5,"longitude":127.0,"accuracyMeters":10,"measuredAt":"${issueAt.minusSeconds(10)}"}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.issue").value("PERMISSION_DENIED"))
        mvc.perform(acceptOffer(actor, offerId, "without-location"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DRIVER_NOT_ELIGIBLE"))
        markReady(actor)
        setAvailability(actor, "UNAVAILABLE").andExpect(status().isOk)
        mvc.perform(acceptOffer(actor, offerId, "while-unavailable"))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("DRIVER_NOT_ELIGIBLE"))
        setAvailability(actor, "READY").andExpect(status().isOk)
        mvc.perform(acceptOffer(actor, offerId)).andExpect(status().isOk)
        assertThat(workOperations.occupied(eligible.driverId)).isTrue()
        mvc.perform(get("/api/v1/service-requests/$requestId").header(HttpHeaders.AUTHORIZATION, otherAdmin.header))
            .andExpect(status().isOk).andExpect(jsonPath("$.requestStatus").value("ACCEPTED"))
        mvc.perform(get("/api/v1/receptions/$receptionId").header(HttpHeaders.AUTHORIZATION, otherAdmin.header)).andExpect(status().isNotFound)
    }

    @Test
    fun `이전 연결의 무효화 결과는 같은 토큰으로 다시 연결한 기사 기기에 영향을 주지 않는다`() {
        // given
        val admin = admin()
        val actor = driver()
        val registered = submit(actor, request())
        decide(admin, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.APPROVE))
        val recipient = OperationsActor(ActorRole.DRIVER, registered.driverId)
        val installation = "ack31-device-rebind"
        val token = "ack31-device-token"
        registerDevice(actor, installation, token)
        val previous = checkNotNull(operationsDevices.current(recipient, installation))

        // when
        mvc.perform(delete("/api/v1/push-devices/$installation").header(HttpHeaders.AUTHORIZATION, actor.header)).andExpect(status().isNoContent)
        assertThat(operationsDevices.current(recipient, installation)).isNull()
        registerDevice(actor, installation, token)
        operationsDevices.invalidateIfCurrent(recipient, previous)

        // then
        val rebound = checkNotNull(operationsDevices.current(recipient, installation))
        assertThat(rebound.bindingVersion).isNotEqualTo(previous.bindingVersion)
        mvc.perform(
            post("/api/v1/auth/sessions/logout").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("refreshToken" to actor.refreshToken))),
        ).andExpect(status().isNoContent)
        assertThat(operationsDevices.current(recipient, installation)).isNull()
    }

    @Test
    fun `가입 결과와 이력 알림 멱등성 기록은 호출자 실패 시 함께 롤백한다`() {
        // given
        val actor = driver()
        val data = request()

        // when
        org.assertj.core.api.Assertions.assertThatThrownBy {
            tx.executeWithoutResult {
                commandService.submit(actor.accountId, "rollback", data.toCommand(), VerifiedBusinessIdentity(data.toCommand().business.identity(), BusinessOperatingStatus.CONTINUING, Instant.now()))
                drivers.flush()
                throw IllegalStateException("test-rollback")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        // then
        assertThat(drivers.existsByAccountId(actor.accountId)).isFalse()
        assertThat(jdbc.queryForObject("select count(*) from driver_idempotency_records where actor_id = ?", Long::class.java, actor.accountId)).isZero()
    }

    @Test
    fun `승인 푸시는 커밋 후 실제 기기 연결로 전송하고 중복 호출해도 다시 보내지 않는다`() {
        // given
        val actor = driver()
        val admin = admin()
        val registered = submit(actor, request())
        registerDevice(actor, "install-approve", "fcm-driver-token")
        decide(admin, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.APPROVE))
        enableFcm(DriverPushResult(messageId = "projects/test/messages/accepted"))

        // when
        repeat(4) { pushWorker.runOnce() }

        // then
        Mockito.verify(fcm, Mockito.times(1)).send(anyTarget(), anyDriverPushData(), anyBeforeSend())
        assertThat(jdbc.queryForObject("select count(*) from driver_push_deliveries where event_id in (select event_id from driver_notification_events where driver_id = ?) and status = 'PROVIDER_ACCEPTED'", Long::class.java, registered.driverId)).isEqualTo(1)
    }

    @Test
    fun `푸시 발송 실패는 승인 상태와 알림 기록을 되돌리지 않는다`() {
        // given
        val actor = driver()
        val admin = admin()
        val registered = submit(actor, request())
        registerDevice(actor, "install-failure", "fcm-failure-token")
        decide(admin, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.APPROVE))
        enableFcm(DriverPushResult(errorCode = "UNAVAILABLE"))

        // when
        repeat(4) { pushWorker.runOnce() }

        // then
        assertThat(drivers.findByAccountId(actor.accountId)?.accountStatus?.name).isEqualTo("ACTIVATED")
        assertThat(jdbc.queryForObject("select count(*) from driver_push_deliveries where event_id in (select event_id from driver_notification_events where driver_id = ?) and status = 'FAILED'", Long::class.java, registered.driverId)).isEqualTo(1)
        Mockito.verify(fcm, Mockito.times(1)).send(anyTarget(), anyDriverPushData(), anyBeforeSend())
    }

    @Test
    fun `무효 FCM 토큰을 폐기하고 다른 계정이 기기 연결을 빼앗거나 해제할 수 없다`() {
        // given
        val actor = driver()
        val other = driver()
        val admin = admin()
        val registered = submit(actor, request())
        registerDevice(actor, "install-invalid", "fcm-invalid-token")
        mvc.perform(delete("/api/v1/push-devices/install-invalid").header(HttpHeaders.AUTHORIZATION, other.header)).andExpect(status().isForbidden)
        mvc.perform(
            put("/api/v1/push-devices/install-invalid").header(HttpHeaders.AUTHORIZATION, other.header)
                .contentType(MediaType.APPLICATION_JSON).content("""{"platform":"ANDROID","pushToken":"other-token"}"""),
        )
            .andExpect(status().isConflict)
        decide(admin, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.APPROVE))
        enableFcm(DriverPushResult(errorCode = "UNREGISTERED", invalidToken = true))

        // when
        repeat(4) { pushWorker.runOnce() }

        // then
        Mockito.verify(fcm, Mockito.times(1)).send(anyTarget(), anyDriverPushData(), anyBeforeSend())
        assertThat(jdbc.queryForObject("select status from driver_push_deliveries where recipient_account_id = ?", String::class.java, actor.accountId)).isEqualTo("FAILED")
        assertThat(jdbc.queryForObject("select error_code from driver_push_deliveries where recipient_account_id = ?", String::class.java, actor.accountId)).isEqualTo("UNREGISTERED")
        assertThat(pushDevices.currentDevice("install-invalid", actor.accountId)).isNull()
        assertThat(jdbc.queryForObject("select push_token from identity_push_devices where installation_id = ?", String::class.java, "install-invalid")).isNull()
    }

    @Test
    fun `로그아웃 이후 기기에 승인 알림을 발송하지 않는다`() {
        // given
        val actor = driver()
        val admin = admin()
        val registered = submit(actor, request())
        registerDevice(actor, "install-logout", "fcm-logout-token")
        decide(admin, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.APPROVE))
        enableFcm(DriverPushResult(messageId = "unused"))
        mvc.perform(post("/api/v1/auth/sessions/logout").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapOf("refreshToken" to actor.refreshToken))))
            .andExpect(status().isNoContent)

        // when
        repeat(4) { pushWorker.runOnce() }

        // then
        Mockito.verify(fcm, Mockito.never()).send(anyTarget(), anyDriverPushData(), anyBeforeSend())
        assertThat(pushDevices.currentDevice("install-logout", actor.accountId)).isNull()
        assertThat(jdbc.queryForObject("select push_token from identity_push_devices where installation_id = ?", String::class.java, "install-logout")).isNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["logout", "token-rotation", "expired-lease"])
    fun `발송 준비 중 기기나 실행권이 바뀌면 최종 확인에서 전송을 취소한다`(scenario: String) {
        // given
        val actor = driver()
        val admin = admin()
        val registered = submit(actor, request())
        registerDevice(actor, "install-checkpoint", "checkpoint-token")
        decide(admin, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.APPROVE))
        enableFcm(DriverPushResult(messageId = "unused"))
        var allowed = true
        Mockito.`when`(fcm.send(anyTarget(), anyDriverPushData(), anyBeforeSend())).thenAnswer {
            check(!TransactionSynchronizationManager.isActualTransactionActive())
            when (scenario) {
                "logout" -> mvc.perform(
                    post("/api/v1/auth/sessions/logout").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(mapOf("refreshToken" to actor.refreshToken))),
                ).andExpect(status().isNoContent)
                "token-rotation" -> registerDevice(actor, "install-checkpoint", "replacement-token")
                "expired-lease" -> jdbc.update(
                    "update driver_push_deliveries set lease_expires_at = ? where recipient_account_id = ?",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(1)),
                    actor.accountId,
                )
            }
            allowed = it.getArgument<() -> Boolean>(2).invoke()
            if (allowed) DriverPushResult(messageId = "unexpected") else null
        }

        // when
        repeat(4) { pushWorker.runOnce() }

        // then
        assertThat(allowed).isFalse()
        assertThat(jdbc.queryForObject("select count(*) from driver_push_deliveries where recipient_account_id = ? and status = 'SKIPPED'", Long::class.java, actor.accountId)).isEqualTo(1)
    }

    @Test
    fun `생성 OpenAPI에 확정한 기사 API와 멱등성 헤더를 노출한다`() {
        // when & then
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['$registrationPath'].post.responses['201']").exists())
            .andExpect(jsonPath("$.paths['$registrationPath'].post.responses['201'].headers['Idempotency-Replayed']").exists())
            .andExpect(jsonPath("$.paths['$registrationPath'].post.responses['429'].headers['Retry-After'].schema.minimum").value(1))
            .andExpect(jsonPath("$.paths['$registrationPath/resubmission'].post.responses['429'].headers['Retry-After'].schema.minimum").value(1))
            .andExpect(jsonPath("$.paths['$registrationPath'].get.responses['429']").doesNotExist())
            .andExpect(jsonPath("$.paths['$registrationPath'].post.parameters[?(@.name == 'Idempotency-Key')]").isNotEmpty())
            .andExpect(jsonPath("$.paths['/api/v1/drivers/{driverId}/approval'].post").exists())
            .andExpect(jsonPath("$.paths['/api/v1/drivers/me/withdrawal'].post").exists())
    }

    @Test
    fun `로그아웃은 해당 세션 기기만 해제하고 탈퇴는 계정의 모든 세션 기기를 해제한다`() {
        // given
        val actor = driver()
        val secondLogin = login.login(checkNotNull(accounts.findById(actor.accountId).orElseThrow().socialLogin))
        val second = Actor(actor.accountId, "Bearer ${secondLogin.accessToken}", secondLogin.refreshToken)
        val admin = admin()
        val registered = submit(actor, request())
        decide(admin, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.APPROVE))
        registerDevice(actor, "install-session-first", "session-first-token")
        registerDevice(second, "install-session-second", "session-second-token")

        // when & then
        mvc.perform(post("/api/v1/auth/sessions/logout").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapOf("refreshToken" to actor.refreshToken))))
            .andExpect(status().isNoContent)
        assertThat(pushDevices.currentDevice("install-session-first", actor.accountId)).isNull()
        assertThat(pushDevices.currentDevice("install-session-second", actor.accountId)).isNotNull()
        mvc.perform(
            post("/api/v1/drivers/me/withdrawal").header(HttpHeaders.AUTHORIZATION, second.header).header("Idempotency-Key", "withdraw")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(DriverWithdrawalRequest(2, true))),
        )
            .andExpect(status().isOk)
        assertThat(pushDevices.activeDevices(listOf(actor.accountId))).isEmpty()
        assertThat(jdbc.queryForObject("select count(*) from identity_push_devices where account_id = ? and push_token is not null", Long::class.java, actor.accountId)).isZero()
        assertThat(sessions.findAllByAccountId(actor.accountId)).allMatch { it.revokedAt != null }
        mvc.perform(get("/api/v1/auth/accounts/me").header(HttpHeaders.AUTHORIZATION, second.header)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `가입 제출은 관리자 기기에 반려는 신청 기사 기기에만 알린다`() {
        // given
        val actor = driver()
        val admin = admin()
        registerDevice(actor, "install-recipient-driver", "recipient-driver-token")
        registerDevice(admin, "install-recipient-admin", "recipient-admin-token")
        val registered = submit(actor, request())
        enableFcm(DriverPushResult(messageId = "accepted"))
        val sent = mutableListOf<Pair<Long, String>>()
        Mockito.`when`(fcm.send(anyTarget(), anyDriverPushData(), anyBeforeSend())).thenAnswer {
            check(!TransactionSynchronizationManager.isActualTransactionActive())
            if (!it.getArgument<() -> Boolean>(2).invoke()) return@thenAnswer null
            sent.add(it.getArgument<PushTarget>(0).accountId to it.getArgument<DriverPushData>(1).eventType)
            DriverPushResult(messageId = "accepted")
        }

        // when
        repeat(3) { pushWorker.runOnce() }
        decide(admin, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.REJECT, "확인 필요"))
        repeat(3) { pushWorker.runOnce() }

        // then
        assertThat(sent).containsExactly(admin.accountId to "REGISTRATION_SUBMITTED", actor.accountId to "DRIVER_REJECTED")
    }

    @Test
    fun `동시 푸시 작업자는 같은 전송을 중복 처리하지 않는다`() {
        // given
        val actor = driver()
        val admin = admin()
        val registered = submit(actor, request())
        registerDevice(actor, "install-concurrent", "concurrent-token")
        decide(admin, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.APPROVE))
        enableFcm(DriverPushResult(messageId = "accepted"))

        // when
        repeat(3) { concurrently({ pushWorker.runOnce() }, { pushWorker.runOnce() }) }

        // then
        Mockito.verify(fcm, Mockito.times(1)).send(anyTarget(), anyDriverPushData(), anyBeforeSend())
    }

    @Test
    fun `중단된 푸시의 임대가 만료되면 재획득하고 이전 작업자의 완료 기록을 무시한다`() {
        // given
        val actor = driver()
        val admin = admin()
        val registered = submit(actor, request())
        registerDevice(actor, "install-lease", "lease-token")
        decide(admin, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.APPROVE))
        repeat(3) { pushTransactions.expandOne() }
        val first = checkNotNull(pushTransactions.claimOne())
        assertThat(pushTransactions.claimOne()).isNull()
        jdbc.update("update driver_push_deliveries set lease_expires_at = ? where delivery_id = ?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)), first.deliveryId)

        // when
        val recovered = checkNotNull(pushTransactions.claimOne())
        pushTransactions.finish(first, DriverPushResult(messageId = "stale"))

        // then
        assertThat(recovered.deliveryId).isEqualTo(first.deliveryId)
        assertThat(recovered.claimId).isNotEqualTo(first.claimId)
        assertThat(pushTransactions.target(first)).isNull()
        assertThat(jdbc.queryForObject("select status from driver_push_deliveries where delivery_id = ?", String::class.java, first.deliveryId)).isEqualTo("PROCESSING")
        pushTransactions.finish(recovered, DriverPushResult(messageId = "recovered"))
        assertThat(jdbc.queryForObject("select provider_message_id from driver_push_deliveries where delivery_id = ?", String::class.java, first.deliveryId)).isEqualTo("recovered")
    }

    @Test
    fun `동일 기기 등록을 재현하고 예전 토큰의 실패로 새 토큰을 폐기하지 않는다`() {
        // given
        val actor = driver()
        val first = registerDevice(actor, "install-rotation", "old-token")
        val oldTarget = checkNotNull(pushDevices.currentDevice("install-rotation", actor.accountId))

        // when & then
        assertThat(registerDevice(actor, "install-rotation", "old-token")).isEqualTo(first)
        registerDevice(actor, "install-rotation", "new-token")
        pushDevices.invalidate(oldTarget)
        assertThat(pushDevices.currentDevice("install-rotation", actor.accountId)?.token).isEqualTo("new-token")
    }

    @Test
    fun `국세청 장애 때 신규 신청은 503이며 완료된 응답 재현과 중복 가입 검사는 외부 호출 없이 처리한다`() {
        // given
        val actor = driver()
        submit(actor, request())
        val newcomer = driver()
        Mockito.`when`(nts.verify(anyIdentity())).thenThrow(BusinessException(DriverErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE))
        Mockito.clearInvocations(nts)

        // when & then
        mvc.perform(
            post(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "submit")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request())),
        )
            .andExpect(status().isCreated).andExpect(header().string("Idempotency-Replayed", "true"))
        assertThat(submitStatus(actor, "another-key")).isEqualTo(409)
        Mockito.verifyNoInteractions(nts)
        assertThat(submitStatus(newcomer, "new")).isEqualTo(503)
        assertThat(drivers.existsByAccountId(newcomer.accountId)).isFalse()
    }

    @Test
    fun `실패한 사업자 검증도 다섯 번 집계하고 차단된 요청은 국세청을 호출하지 않는다`() {
        // given
        val actor = driver()
        val other = driver()
        Mockito.`when`(nts.verify(anyIdentity())).thenThrow(BusinessException(DriverErrorCode.BUSINESS_INFO_INVALID))
        repeat(5) { assertThat(submitStatus(actor, "failed-$it")).isEqualTo(400) }

        // when & then
        val response = mvc.perform(
            post(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "blocked")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request())),
        ).andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn().response
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)?.toLong()).isBetween(1L, 60L)
        Mockito.verify(nts, Mockito.times(5)).verify(anyIdentity())
        mvc.perform(get(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header)).andExpect(status().isNotFound)
        assertThat(submitStatus(other, "other-account")).isEqualTo(400)

        jdbc.update("update request_limit_buckets set window_ends_at = statement_timestamp() - interval '1 second' where bucket_id = ?", "driver-registration:${actor.accountId}")
        assertThat(submitStatus(actor, "after-expiry")).isEqualTo(400)
        assertThat(drivers.existsByAccountId(actor.accountId)).isFalse()
    }

    @Test
    fun `가입과 재신청 및 성공 결과의 멱등 재전송을 같은 한도로 합산한다`() {
        // given
        val actor = driver()
        val reviewer = admin()
        val registered = submit(actor, request())
        decide(reviewer, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.REJECT, "재확인"))
        val resubmission = mapper.writeValueAsString(DriverResubmissionRequest(2, request()))
        mvc.perform(
            post("$registrationPath/resubmission").header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "resubmit")
                .contentType(MediaType.APPLICATION_JSON).content(resubmission),
        ).andExpect(status().isOk)
        repeat(2) { assertThat(submitStatus(actor, "submit")).isEqualTo(201) }
        mvc.perform(
            post("$registrationPath/resubmission").header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "resubmit")
                .contentType(MediaType.APPLICATION_JSON).content(resubmission),
        ).andExpect(status().isOk).andExpect(header().string("Idempotency-Replayed", "true"))

        // when & then
        mvc.perform(
            post("$registrationPath/resubmission").header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "resubmit")
                .contentType(MediaType.APPLICATION_JSON).content(resubmission),
        ).andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
        assertThat(submitStatus(actor, "submit")).isEqualTo(429)
        Mockito.verify(nts, Mockito.times(2)).verify(anyIdentity())
        mvc.perform(get(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header)).andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(3))
    }

    @Test
    fun `재신청 이후 이전 반려 알림은 발송하지 않는다`() {
        // given
        val actor = driver()
        val admin = admin()
        val registered = submit(actor, request())
        registerDevice(actor, "install-stale", "stale-token")
        decide(admin, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.REJECT, "확인 필요"))
        mvc.perform(
            post("$registrationPath/resubmission").header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "resubmit")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(DriverResubmissionRequest(2, request()))),
        )
            .andExpect(status().isOk)
        enableFcm(DriverPushResult(messageId = "unused"))

        // when
        repeat(5) { pushWorker.runOnce() }

        // then
        Mockito.verify(fcm, Mockito.never()).send(anyTarget(), anyDriverPushData(), anyBeforeSend())
        assertThat(jdbc.queryForObject("select count(*) from driver_notification_events where driver_id = ? and event_type = 'DRIVER_REJECTED' and status = 'SKIPPED'", Long::class.java, registered.driverId)).isEqualTo(1)
    }

    @Test
    fun `가입 전 사업자 확인은 신청을 생성하지 않으며 최종 신청에서 다시 검증한다`() {
        // given
        val actor = driver()
        val request = BusinessVerificationRequest("1234567890", LocalDate.of(2020, 1, 15), "홍길동")

        // when
        mvc.perform(
            post("/api/v1/drivers/business-verifications").header(HttpHeaders.AUTHORIZATION, actor.header)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)),
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.status").value("VERIFIED"))
            .andExpect(jsonPath("$.verifiedAt").exists())

        // then
        assertThat(drivers.findByAccountId(actor.accountId)).isNull()
        submit(actor, request())
        Mockito.verify(nts, Mockito.times(2)).verify(anyIdentity())
    }

    @Test
    fun `사업자 확인은 기사 인증과 엄격한 세 가지 입력을 요구한다`() {
        // given
        val path = "/api/v1/drivers/business-verifications"
        val valid = """{"businessRegistrationNumber":"1234567890","openedOn":"2020-01-15","representativeName":"홍길동"}"""
        val actor = driver()
        val administrator = admin()

        // when & then
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isUnauthorized)
        mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, administrator.header).contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isForbidden)
        listOf(
            valid.replace("1234567890", "123"),
            valid.replace("2020-01-15", "2020-02-30"),
            valid.replace("홍길동", " "),
            valid.replace("}", ""","verified":true}"""),
            "{}",
        ).forEach { body ->
            mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, actor.header).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest)
        }
        Mockito.verify(nts, Mockito.never()).verify(anyIdentity())
    }

    @Test
    fun `휴업 폐업과 외부 검증 장애는 사업자 확인 성공으로 처리하지 않는다`() {
        // given
        val actor = driver()
        val path = "/api/v1/drivers/business-verifications"
        val body = """{"businessRegistrationNumber":"1234567890","openedOn":"2020-01-15","representativeName":"홍길동"}"""

        // when & then
        listOf(BusinessOperatingStatus.SUSPENDED, BusinessOperatingStatus.CLOSED).forEach { operating ->
            Mockito.`when`(nts.verify(anyIdentity())).thenAnswer { VerifiedBusinessIdentity(it.getArgument(0), operating, Instant.now()) }
            mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, actor.header).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("BUSINESS_INFO_INVALID"))
        }
        Mockito.`when`(nts.verify(anyIdentity())).thenThrow(BusinessException(DriverErrorCode.BUSINESS_VERIFICATION_UNAVAILABLE))
        mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, actor.header).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isServiceUnavailable).andExpect(jsonPath("$.code").value("BUSINESS_VERIFICATION_UNAVAILABLE"))
        assertThat(drivers.findByAccountId(actor.accountId)).isNull()
    }

    private fun registerDevice(actor: Actor, installation: String, token: String): String = mvc.perform(
        put("/api/v1/push-devices/$installation").header(HttpHeaders.AUTHORIZATION, actor.header)
            .contentType(MediaType.APPLICATION_JSON).content("""{"platform":"ANDROID","pushToken":"$token"}"""),
    )
        .andExpect(status().isOk).andExpect(jsonPath("$.pushToken").doesNotExist()).andReturn().response.contentAsString

    private fun enableFcm(result: DriverPushResult) {
        Mockito.`when`(fcm.isConfigured()).thenReturn(true)
        Mockito.`when`(fcm.send(anyTarget(), anyDriverPushData(), anyBeforeSend())).thenAnswer {
            check(!TransactionSynchronizationManager.isActualTransactionActive())
            if (!it.getArgument<() -> Boolean>(2).invoke()) return@thenAnswer null
            result
        }
    }

    private fun setAvailability(actor: Actor, availability: String) = mvc.perform(
        patch("/api/v1/drivers/me/availability").header(HttpHeaders.AUTHORIZATION, actor.header)
            .contentType(MediaType.APPLICATION_JSON).content("""{"availability":"$availability"}"""),
    )

    private fun markReady(actor: Actor) {
        setAvailability(actor, "READY").andExpect(status().isOk)
        val now = Instant.now()
        sendLocation(actor, """{"latitude":37.5,"longitude":127.0,"accuracyMeters":10,"measuredAt":"$now"}""")
            .andExpect(status().isOk).andExpect(jsonPath("$.locationUsability").value("VALID"))
    }

    private fun sendLocation(actor: Actor, body: String) = mvc.perform(
        put("/api/v1/drivers/me/location").header(HttpHeaders.AUTHORIZATION, actor.header)
            .contentType(MediaType.APPLICATION_JSON).content(body),
    )

    private fun acceptOffer(actor: Actor, offerId: String, key: String = "accept") = post("/api/v1/dispatch-offers/$offerId/response").header(HttpHeaders.AUTHORIZATION, actor.header)
        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("""{"response":"ACCEPT"}""")

    private fun offerFor(adminId: Long, driverId: Long): OfferSnapshot {
        val reception = receptions.create(adminId, ReceptionCreationData())
        val id = ReceptionExternalId.RECEPTION.parse(reception.receptionId)
        val now = Instant.now()
        val from = now.atOffset(ZoneOffset.UTC).plusHours(1)
        val intake = RequestIntake(com.allcheck.operations.WorkType.BLOCKAGE, "온수 이상", "서울 강남구", from, from.plusHours(1), "01012345678", null)
        return checkNotNull(
            tx.execute {
                val request = requests.create(id, adminId, intake, SiteCoordinates(127.0, 37.5, "서울 강남구"), now)
                val round = dispatch.create(request.requestId, now)
                requests.startRound(request.requestId, round.roundId, now)
                dispatch.publish(round.roundId, mapOf(driverId to 0.0), now, Duration.ofMinutes(1)).single()
            },
        )
    }

    private fun anyTarget(): PushTarget = any(PushTarget::class.java) ?: PushTarget(1L, "unused", "unused", "unused", 0)
    private fun anyDriverPushData(): DriverPushData = any(DriverPushData::class.java) ?: DriverPushData(1L, "unused", 1L, 1)
    private fun anyBeforeSend(): () -> Boolean = any<() -> Boolean>() ?: { true }

    private fun submitStatus(actor: Actor, key: String): Int = mvc.perform(
        post(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header)
            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request())),
    ).andReturn().response.status

    private fun concurrently(first: () -> Any, second: () -> Any): List<Any> {
        val ready = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(first, second).map { action ->
                executor.submit<Any> {
                    ready.countDown()
                    check(ready.await(5, TimeUnit.SECONDS))
                    action()
                }
            }
            return futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun submit(actor: Actor, request: DriverRegistrationRequest): DriverRegistrationResult {
        val response = mvc.perform(
            post(registrationPath).header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "submit")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)),
        )
            .andExpect(status().isCreated).andReturn().response
        return mapper.readValue(response.contentAsString, DriverRegistrationResult::class.java)
    }

    private fun decide(actor: Actor, driverId: Long, request: DriverApprovalRequest) {
        mvc.perform(
            post("/api/v1/drivers/$driverId/approval").header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "decision")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)),
        ).andExpect(status().isOk)
    }

    private fun driver(): Actor {
        val response = login.login(SocialLogin.kakao(nextUser.incrementAndGet()))
        ownAccounts.add(response.user.accountId)
        return Actor(response.user.accountId, "Bearer ${response.accessToken}", response.refreshToken)
    }

    private fun admin(role: AdminRole = AdminRole.MASTER): Actor = tx.execute {
        val now = Instant.now()
        val account = accounts.saveAndFlush(Account.administrator(AdminCredential("driver-admin-${UUID.randomUUID()}", "test-password-hash", role), now))
        ownAccounts.add(checkNotNull(account.id))
        val session = sessions.saveAndFlush(AuthSession(checkNotNull(account.id), refreshTokens.hash(refreshTokens.generate()), now, now.plusSeconds(3600)))
        Actor(checkNotNull(account.id), "Bearer ${tokens.createAccessToken(checkNotNull(account.id), checkNotNull(session.id))}")
    }

    private fun request(workerName: String = "홍길동"): DriverRegistrationRequest = DriverRegistrationRequest(
        DriverProfileRequest(workerName, "01012345678", "driver@example.com", "서울시", "12가3456", SettlementAccountRequest("예시은행", "123456789012", "홍길동"), "tax@example.com", listOf(WorkType.BLOCKAGE), listOf("서울"), listOf("장비")),
        BusinessInfoRequest("길동설비", "1234567890", LocalDate.of(2020, 1, 15), "서비스업", "배관", "홍길동", "0212345678", "서울시"),
    )

    private data class Actor(val accountId: Long, val header: String, val refreshToken: String = "unused")

    private fun anyIdentity(): BusinessIdentity = any(BusinessIdentity::class.java) ?: BusinessIdentity("1234567890", LocalDate.of(2020, 1, 15), "테스트대표")
    companion object {
        private val nextUser = AtomicLong(810000000)
        private val database = PostgresTestSchema("driver_registration")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            database.register(registry)
        }
    }
}
