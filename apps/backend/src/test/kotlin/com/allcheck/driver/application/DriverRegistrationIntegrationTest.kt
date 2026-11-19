package com.allcheck.driver.application

import com.allcheck.driver.domain.vo.BusinessIdentity
import com.allcheck.driver.domain.vo.BusinessOperatingStatus
import com.allcheck.driver.domain.vo.VerifiedBusinessIdentity
import com.allcheck.driver.domain.vo.WorkType
import com.allcheck.driver.exception.DriverErrorCode
import com.allcheck.driver.infrastructure.business.NtsBusinessClient
import com.allcheck.driver.infrastructure.push.FcmClient
import com.allcheck.driver.infrastructure.push.FcmData
import com.allcheck.driver.infrastructure.push.FcmSendResult
import com.allcheck.driver.infrastructure.repository.DriverRepository
import com.allcheck.driver.presentation.dto.request.ApprovalDecision
import com.allcheck.driver.presentation.dto.request.BusinessInfoRequest
import com.allcheck.driver.presentation.dto.request.BusinessVerificationRequest
import com.allcheck.driver.presentation.dto.request.DriverApprovalRequest
import com.allcheck.driver.presentation.dto.request.DriverProfileRequest
import com.allcheck.driver.presentation.dto.request.DriverRegistrationRequest
import com.allcheck.driver.presentation.dto.request.DriverResubmissionRequest
import com.allcheck.driver.presentation.dto.request.DriverWithdrawalRequest
import com.allcheck.driver.presentation.dto.request.SettlementAccountRequest
import com.allcheck.driver.presentation.dto.response.DriverRegistrationResult
import com.allcheck.global.exception.BusinessException
import com.allcheck.global.security.jwt.JwtTokenProvider
import com.allcheck.identity.AdminRole
import com.allcheck.identity.PushDeviceOperations
import com.allcheck.identity.PushTarget
import com.allcheck.identity.application.KakaoSessionService
import com.allcheck.identity.domain.entity.Account
import com.allcheck.identity.domain.entity.AuthSession
import com.allcheck.identity.domain.vo.AdminCredential
import com.allcheck.identity.domain.vo.SocialLogin
import com.allcheck.identity.infrastructure.repository.AccountRepository
import com.allcheck.identity.infrastructure.repository.AuthSessionRepository
import com.allcheck.identity.infrastructure.token.RefreshTokenProvider
import com.allcheck.operations.work.WorkOperations
import com.allcheck.operations.work.WorkReference
import com.allcheck.operations.work.domain.entity.WorkOrder
import com.allcheck.operations.work.domain.entity.WorkStatus
import com.allcheck.operations.work.infrastructure.repository.WorkOrderRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@SpringBootTest(
    properties = [
        "spring.datasource.url=\${IDENTITY_TEST_DATABASE_URL:jdbc:postgresql://localhost:15432/allcheck_identity_test}",
        "spring.datasource.username=\${IDENTITY_TEST_DATABASE_USER:allcheck}",
        "spring.datasource.password=\${IDENTITY_TEST_DATABASE_PASSWORD:allcheck_local_6}",
        "spring.jpa.show-sql=false",
    ],
)
@AutoConfigureMockMvc
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
    private val works: WorkOrderRepository,
    private val jdbc: JdbcTemplate,
    private val pushWorker: DriverPushWorker,
    private val pushTransactions: DriverPushTransactions,
    private val pushDevices: PushDeviceOperations,
    private val workOperations: WorkOperations,
    private val commandService: DriverCommandService,
    transactionManager: PlatformTransactionManager,
) {
    @MockitoBean private lateinit var nts: NtsBusinessClient

    @MockitoBean private lateinit var fcm: FcmClient
    private val tx = TransactionTemplate(transactionManager)
    private val ownAccounts = mutableListOf<String>()
    private val registrationPath = "/api/v1/drivers/me/registration"

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
                val profileId = driver.profile.id
                val businessId = driver.business.id
                jdbc.update("delete from work_orders where driver_id = ?", driver.id)
                jdbc.update("delete from driver_push_deliveries where event_id in (select event_id from driver_notification_events where driver_id = ?)", driver.id)
                jdbc.update("delete from driver_notification_events where driver_id = ?", driver.id)
                jdbc.update("delete from driver_registration_history where driver_id = ?", driver.id)
                jdbc.update("delete from drivers where driver_id = ?", driver.id)
                listOf("driver_profile_specialties", "driver_profile_regions", "driver_profile_equipment").forEach { table -> jdbc.update("delete from $table where profile_id = ?", profileId) }
                jdbc.update("delete from driver_profiles where profile_id = ?", profileId)
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

        // when
        mvc.perform(
            post("$registrationPath/resubmission").header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "resubmit")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(DriverResubmissionRequest(2, request("수정기사")))),
        )
            .andExpect(status().isOk).andExpect(jsonPath("$.driverId").value(registered.driverId)).andExpect(jsonPath("$.version").value(3))

        // then
        mvc.perform(get("/api/v1/drivers/${registered.driverId}").header(HttpHeaders.AUTHORIZATION, admin.header))
            .andExpect(status().isOk).andExpect(jsonPath("$.rejectionHistory[0].reason").value("서류 확인 필요"))
            .andExpect(jsonPath("$.profile.workerName").value("수정기사"))
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
        tx.execute { works.saveAndFlush(WorkOrder("request-${UUID.randomUUID()}", registered.driverId, workStatus, Instant.now())) }
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
                    commandService.submit(actor.accountId, "submit", data, VerifiedBusinessIdentity(data.business.identity(), BusinessOperatingStatus.CONTINUING, Instant.now()))
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
        assertThat(jdbc.queryForObject("select count(*) from driver_registration_history where driver_id = ?", Long::class.java, driver.id)).isEqualTo(1)
    }

    @Test
    fun `새 작업 배정과 탈퇴가 경쟁해도 탈퇴 기사에게 미완료 작업이 생성되지 않는다`() {
        // given
        val actor = driver()
        val admin = admin()
        val registered = submit(actor, request())
        decide(admin, registered.driverId, DriverApprovalRequest(1, ApprovalDecision.APPROVE))

        // when
        val results = concurrently(
            {
                try {
                    workOperations.recordAssignment("request-${UUID.randomUUID()}", registered.driverId)
                } catch (ex: BusinessException) {
                    ex.errorCode
                }
            },
            {
                mvc.perform(
                    post("/api/v1/drivers/me/withdrawal").header(HttpHeaders.AUTHORIZATION, actor.header).header("Idempotency-Key", "withdraw")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(DriverWithdrawalRequest(2, true))),
                )
                    .andReturn().response.status
            },
        )

        // then
        val assigned = results.first() is WorkReference
        assertThat(results.last()).isEqualTo(if (assigned) 409 else 200)
        assertThat(accounts.findById(actor.accountId).orElseThrow().enabled).isEqualTo(assigned)
        assertThat(works.existsByDriverIdAndStatusNotIn(registered.driverId, listOf(WorkStatus.WORK_DONE, WorkStatus.CANCELLED))).isEqualTo(assigned)
    }

    @Test
    fun `가입 결과와 이력 알림 멱등성 기록은 호출자 실패 시 함께 롤백한다`() {
        // given
        val actor = driver()
        val data = request()

        // when
        org.assertj.core.api.Assertions.assertThatThrownBy {
            tx.executeWithoutResult {
                commandService.submit(actor.accountId, "rollback", data, VerifiedBusinessIdentity(data.business.identity(), BusinessOperatingStatus.CONTINUING, Instant.now()))
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
        enableFcm(FcmSendResult(messageId = "projects/test/messages/accepted"))

        // when
        repeat(4) { pushWorker.runOnce() }

        // then
        Mockito.verify(fcm, Mockito.times(1)).send(anyTarget(), anyFcmData())
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
        enableFcm(FcmSendResult(errorCode = "UNAVAILABLE"))

        // when
        repeat(4) { pushWorker.runOnce() }

        // then
        assertThat(drivers.findByAccountId(actor.accountId)?.accountStatus?.name).isEqualTo("ACTIVATED")
        assertThat(jdbc.queryForObject("select count(*) from driver_push_deliveries where event_id in (select event_id from driver_notification_events where driver_id = ?) and status = 'FAILED'", Long::class.java, registered.driverId)).isEqualTo(1)
        Mockito.verify(fcm, Mockito.times(1)).send(anyTarget(), anyFcmData())
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
        enableFcm(FcmSendResult(errorCode = "UNREGISTERED", invalidToken = true))

        // when
        repeat(4) { pushWorker.runOnce() }

        // then
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
        enableFcm(FcmSendResult(messageId = "unused"))
        mvc.perform(post("/api/v1/auth/sessions/logout").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapOf("refreshToken" to actor.refreshToken))))
            .andExpect(status().isNoContent)

        // when
        repeat(4) { pushWorker.runOnce() }

        // then
        Mockito.verify(fcm, Mockito.never()).send(anyTarget(), anyFcmData())
        assertThat(pushDevices.currentDevice("install-logout", actor.accountId)).isNull()
        assertThat(jdbc.queryForObject("select push_token from identity_push_devices where installation_id = ?", String::class.java, "install-logout")).isNull()
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
        enableFcm(FcmSendResult(messageId = "accepted"))
        val sent = mutableListOf<Pair<String, String>>()
        Mockito.`when`(fcm.send(anyTarget(), anyFcmData())).thenAnswer {
            check(!TransactionSynchronizationManager.isActualTransactionActive())
            sent.add(it.getArgument<PushTarget>(0).accountId to it.getArgument<FcmData>(1).eventType)
            FcmSendResult(messageId = "accepted")
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
        enableFcm(FcmSendResult(messageId = "accepted"))

        // when
        repeat(3) { concurrently({ pushWorker.runOnce() }, { pushWorker.runOnce() }) }

        // then
        Mockito.verify(fcm, Mockito.times(1)).send(anyTarget(), anyFcmData())
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
        pushTransactions.finish(first, FcmSendResult(messageId = "stale"))

        // then
        assertThat(recovered.deliveryId).isEqualTo(first.deliveryId)
        assertThat(recovered.claimId).isNotEqualTo(first.claimId)
        assertThat(jdbc.queryForObject("select status from driver_push_deliveries where delivery_id = ?", String::class.java, first.deliveryId)).isEqualTo("PROCESSING")
        pushTransactions.finish(recovered, FcmSendResult(messageId = "recovered"))
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
        enableFcm(FcmSendResult(messageId = "unused"))

        // when
        repeat(5) { pushWorker.runOnce() }

        // then
        Mockito.verify(fcm, Mockito.never()).send(anyTarget(), anyFcmData())
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

    private fun enableFcm(result: FcmSendResult) {
        Mockito.`when`(fcm.isConfigured()).thenReturn(true)
        Mockito.`when`(fcm.send(anyTarget(), anyFcmData())).thenAnswer {
            check(!TransactionSynchronizationManager.isActualTransactionActive())
            result
        }
    }

    private fun anyTarget(): PushTarget = any(PushTarget::class.java) ?: PushTarget("unused", "unused", "unused", "unused")
    private fun anyFcmData(): FcmData = any(FcmData::class.java) ?: FcmData("unused", "unused", "unused", 1)

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

    private fun decide(actor: Actor, driverId: String, request: DriverApprovalRequest) {
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
        ownAccounts.add(account.id)
        val session = sessions.saveAndFlush(AuthSession(account.id, refreshTokens.hash(refreshTokens.generate()), now, now.plusSeconds(3600)))
        Actor(account.id, "Bearer ${tokens.createAccessToken(account.id, session.id)}")
    }

    private fun request(workerName: String = "홍길동"): DriverRegistrationRequest = DriverRegistrationRequest(
        DriverProfileRequest(workerName, "01012345678", "driver@example.com", "서울시", "12가3456", SettlementAccountRequest("예시은행", "123456789012", "홍길동"), "tax@example.com", listOf(WorkType.BLOCKAGE), listOf("서울"), listOf("장비")),
        BusinessInfoRequest("길동설비", "1234567890", LocalDate.of(2020, 1, 15), "서비스업", "배관", "홍길동", "0212345678", "서울시"),
    )

    private data class Actor(val accountId: String, val header: String, val refreshToken: String = "unused")

    private fun anyIdentity(): BusinessIdentity = any(BusinessIdentity::class.java) ?: BusinessIdentity("1234567890", LocalDate.of(2020, 1, 15), "테스트대표")
    companion object {
        private val nextUser = AtomicLong(810000000)
    }
}
