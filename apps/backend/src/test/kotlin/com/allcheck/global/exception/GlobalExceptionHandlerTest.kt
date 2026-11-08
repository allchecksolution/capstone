package com.allcheck.global.exception

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.validation.Errors
import org.springframework.validation.Validator
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.WebDataBinder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.InitBinder
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper

@WebMvcTest(
    controllers = [GlobalExceptionHandlerTest.TestController::class, GlobalExceptionHandlerTest.ValidatedController::class],
)
@Import(
    GlobalExceptionHandler::class,
    GlobalExceptionHandlerTest.TestController::class,
    GlobalExceptionHandlerTest.ValidatedController::class,
)
@WithMockUser
@ExtendWith(OutputCaptureExtension::class)
class GlobalExceptionHandlerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `비즈니스 예외는 에러 코드의 상태와 응답을 반환하고 원인 정보를 노출하지 않는다`(output: CapturedOutput) {
        // given
        val request = get("/api/v1/exception-tests/business")

        // when
        val result = mockMvc.perform(request)

        // then
        val response = result
            .andExpect(status().isConflict)
            .andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.code").value("TEST_BUSINESS_ERROR"))
            .andExpect(jsonPath("$.message").value("테스트 비즈니스 오류입니다."))
            .andReturn().response
        val json = objectMapper.readTree(response.contentAsString)
        assertThat(json.has("details")).isFalse()
        assertThat(json.properties().map { it.key }).containsExactlyInAnyOrder("code", "message")
        assertThat(response.contentAsString)
            .doesNotContain("sensitive-cause", "IllegalStateException", "stackTrace")
        assertThat(output.all)
            .contains("code=TEST_BUSINESS_ERROR")
            .doesNotContain("sensitive-cause", "IllegalStateException")
    }

    @Test
    fun `검증 상세가 있는 응답은 details 배열을 직렬화한다`() {
        // given
        val details = listOf(ValidationError(field = "name", reason = "필수 입력값입니다."))
        val response = ErrorResponse.from(GlobalErrorCode.INVALID_INPUT_VALUE, details)

        // when
        val json = objectMapper.readTree(objectMapper.writeValueAsString(response))

        // then
        assertThat(json["code"].asString()).isEqualTo("INVALID_INPUT_VALUE")
        assertThat(json["message"].asString()).isEqualTo("입력값이 올바르지 않습니다.")
        assertThat(json["details"].isArray).isTrue()
        assertThat(json["details"].size()).isEqualTo(1)
        assertThat(json["details"][0]["field"].asString()).isEqualTo("name")
        assertThat(json["details"][0]["reason"].asString()).isEqualTo("필수 입력값입니다.")
        assertThat(json["details"][0].properties().map { it.key })
            .containsExactlyInAnyOrder("field", "reason")
    }

    @Test
    fun `빈 검증 상세 목록도 details 배열로 직렬화한다`() {
        // given
        val response = ErrorResponse.from(TestErrorCode.TEST_BUSINESS_ERROR, emptyList())

        // when
        val json = objectMapper.readTree(objectMapper.writeValueAsString(response))

        // then
        assertThat(json.has("details")).isTrue()
        assertThat(json["details"].isArray).isTrue()
        assertThat(json["details"].size()).isZero()
    }

    @Test
    fun `요청 본문 검증 실패는 400과 필드별 상세를 반환하고 입력값을 로그에 남기지 않는다`(output: CapturedOutput) {
        // given
        val request = post("/api/v1/exception-tests/validation")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"private-validation-input"}""")

        // when
        val result = mockMvc.perform(request)

        // then
        result.andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
            .andExpect(jsonPath("$.message").value("입력값이 올바르지 않습니다."))
            .andExpect(jsonPath("$.details[0].field").value("name"))
            .andExpect(jsonPath("$.details[0].reason").value("이름은 3자 이하여야 합니다."))
        assertThat(result.andReturn().resolvedException).isInstanceOf(MethodArgumentNotValidException::class.java)
        assertThat(output.all)
            .containsPattern("""\bfields\s*=.*\bname\b""")
            .containsPattern("""\berrorCount\s*=\s*1\b""")
            .doesNotContain("private-validation-input", "이름은 3자 이하여야 합니다.")
    }

    @Test
    fun `요청 본문의 여러 필드 검증 오류는 모든 상세와 전체 오류 개수를 반환한다`(output: CapturedOutput) {
        // given
        val request = post("/api/v1/exception-tests/multiple-field-validation")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"private-name-input","count":0}""")

        // when
        val result = mockMvc.perform(request)

        // then
        val mvcResult = result.andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
            .andExpect(jsonPath("$.details").isArray)
            .andReturn()
        val details = objectMapper.readTree(mvcResult.response.contentAsString)["details"].values().map {
            it["field"].asString() to it["reason"].asString()
        }
        assertThat(details).hasSize(2).containsExactlyInAnyOrder(
            "name" to "이름은 3자 이하여야 합니다.",
            "count" to "개수는 1 이상이어야 합니다.",
        )
        assertThat(mvcResult.resolvedException).isInstanceOf(MethodArgumentNotValidException::class.java)
        assertThat(output.all)
            .containsPattern("""\bfields\s*=.*\bname\b""")
            .containsPattern("""\bfields\s*=.*\bcount\b""")
            .containsPattern("""\berrorCount\s*=\s*2\b""")
            .doesNotContain("private-name-input", "이름은 3자 이하여야 합니다.", "개수는 1 이상이어야 합니다.")
    }

    @Test
    fun `객체 검증에 필드명과 기본 메시지가 없으면 안전한 기본 상세를 반환한다`() {
        // given
        val request = post("/api/v1/exception-tests/object-validation")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"name":"ok"}""")

        // when
        val result = mockMvc.perform(request)

        // then
        result.andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.details[0].field").value("request"))
            .andExpect(jsonPath("$.details[0].reason").value("입력값이 올바르지 않습니다."))
    }

    @Test
    fun `모델 바인딩 실패의 상세에는 입력값과 내부 타입 정보를 포함하지 않는다`(output: CapturedOutput) {
        // given
        val request = get("/api/v1/exception-tests/model").param("count", "private-binding-input")

        // when
        val result = mockMvc.perform(request)

        // then
        val response = result.andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
            .andExpect(jsonPath("$.details[0].field").value("count"))
            .andExpect(jsonPath("$.details[0].reason").value("입력값이 올바르지 않습니다."))
            .andReturn().response
        assertThat(response.contentAsString).doesNotContain("private-binding-input", "java.lang", "typeMismatch")
        assertThat(output.all)
            .containsPattern("""\bfields\s*=.*\bcount\b""")
            .containsPattern("""\berrorCount\s*=\s*1\b""")
            .doesNotContain("private-binding-input")
    }

    @Test
    fun `검증 필드 경로에 포함된 Map 키도 로그에 남기지 않는다`(output: CapturedOutput) {
        // given
        val request = post("/api/v1/exception-tests/map-validation")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"profiles":{"private-map-key":{"name":"private-map-value"}}}""")

        // when
        val result = mockMvc.perform(request)

        // then
        result.andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.details[0].field").value("profiles[private-map-key].name"))
        assertThat(output.all)
            .containsPattern("""\bfields\s*=.*\bprofiles\b""")
            .containsPattern("""\berrorCount\s*=\s*1\b""")
            .doesNotContain("private-map-key", "private-map-value")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"name":"private-json-input",}""",
            """{"name":{"value":"private-json-input"}}""",
            """{"name":null}""",
            "{}",
        ],
    )
    fun `잘못된 JSON과 역직렬화 실패는 상세 없는 400 응답을 반환한다`(body: String, output: CapturedOutput) {
        // given
        val request = post("/api/v1/exception-tests/validation")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)

        // when
        val result = mockMvc.perform(request)

        // then
        val response = result.andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
            .andExpect(jsonPath("$.message").value("입력값이 올바르지 않습니다."))
            .andReturn().response
        assertThat(objectMapper.readTree(response.contentAsString).has("details")).isFalse()
        assertThat(response.contentAsString).doesNotContain("private-json-input", "Exception", "TestRequest")
        assertThat(output.all)
            .containsPattern("""\bstatus\s*=\s*400\b""")
            .doesNotContain("private-json-input")
    }

    @ParameterizedTest
    @ValueSource(strings = ["/query", "/header"])
    fun `필수 요청 파라미터와 헤더 누락은 400 응답을 반환한다`(endpoint: String) {
        // given
        val request = get("/api/v1/exception-tests$endpoint")

        // when
        val result = mockMvc.perform(request)

        // then
        val response = result.andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
            .andReturn().response
        assertThat(objectMapper.readTree(response.contentAsString).has("details")).isFalse()
    }

    @Test
    fun `요청 파라미터 타입 변환 실패는 입력값을 노출하지 않고 400을 반환한다`(output: CapturedOutput) {
        // given
        val request = get("/api/v1/exception-tests/query").param("count", "private-query-input")

        // when
        val result = mockMvc.perform(request)

        // then
        val response = result.andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
            .andReturn().response
        assertThat(response.contentAsString).doesNotContain("private-query-input", "java.lang", "Exception")
        assertThat(output.all)
            .containsPattern("""\bstatus\s*=\s*400\b""")
            .doesNotContain("private-query-input")
    }

    @Test
    fun `MVC 메서드 요청 검증은 실제 요청 파라미터 이름과 함께 400을 반환한다`() {
        // given
        val request = get("/api/v1/exception-tests/method-validation").param("page-size", "0")

        // when
        val result = mockMvc.perform(request)

        // then
        result.andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
            .andExpect(jsonPath("$.details[0].field").value("page-size"))
            .andExpect(jsonPath("$.details[0].reason").value("페이지 크기는 1 이상이어야 합니다."))
        assertThat(result.andReturn().resolvedException).isInstanceOf(HandlerMethodValidationException::class.java)
    }

    @Test
    fun `MVC 메서드 검증에 포함된 중첩 본문 필드 경로를 유지한다`() {
        // given
        val request = post("/api/v1/exception-tests/nested-method-validation")
            .with(csrf())
            .param("count", "1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"profile":{"name":"private-nested-input"}}""")

        // when
        val result = mockMvc.perform(request)

        // then
        result.andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
            .andExpect(jsonPath("$.details[0].field").value("profile.name"))
        assertThat(result.andReturn().resolvedException).isInstanceOf(HandlerMethodValidationException::class.java)
    }

    @Test
    fun `MVC 메서드 검증은 요청 파라미터와 중첩 본문의 오류를 모두 반환한다`() {
        // given
        val request = post("/api/v1/exception-tests/nested-method-validation")
            .with(csrf())
            .param("count", "0")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"profile":{"name":"private-nested-input"}}""")

        // when
        val result = mockMvc.perform(request)

        // then
        val mvcResult = result.andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
            .andExpect(jsonPath("$.details").isArray)
            .andReturn()
        val details = objectMapper.readTree(mvcResult.response.contentAsString)["details"].values().map {
            it["field"].asString() to it["reason"].asString()
        }
        assertThat(details).hasSize(2).containsExactlyInAnyOrder(
            "count" to "개수는 1 이상이어야 합니다.",
            "profile.name" to "이름은 3자 이하여야 합니다.",
        )
        assertThat(mvcResult.resolvedException).isInstanceOf(HandlerMethodValidationException::class.java)
    }

    @Test
    fun `Validated 컨트롤러의 제약 위반은 400과 필드 상세를 반환한다`() {
        // given
        val request = get("/api/v1/exception-tests/constraint-validation").param("count", "0")

        // when
        val result = mockMvc.perform(request)

        // then
        result.andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
            .andExpect(jsonPath("$.details[0].field").value("count"))
            .andExpect(jsonPath("$.details[0].reason").value("개수는 1 이상이어야 합니다."))
        assertThat(result.andReturn().resolvedException).isInstanceOf(ConstraintViolationException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/return-validation", "/constraint-return-validation"])
    fun `반환값 검증 실패는 요청 오류와 구분하여 상세 없는 500을 반환한다`(endpoint: String, output: CapturedOutput) {
        // given
        val request = get("/api/v1/exception-tests$endpoint")

        // when
        val result = mockMvc.perform(request)

        // then
        val response = result.andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
            .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
            .andReturn().response
        assertThat(objectMapper.readTree(response.contentAsString).has("details")).isFalse()
        assertThat(response.contentAsString).doesNotContain("private-return-input", "Exception")
        assertThat(output.all)
            .contains("ERROR")
            .containsAnyOf(HandlerMethodValidationException::class.java.name, ConstraintViolationException::class.java.name)
            .doesNotContain("private-return-input")
    }

    @Test
    fun `지원하지 않는 HTTP 메서드는 405와 Allow 헤더를 반환한다`() {
        // given
        val request = post("/api/v1/exception-tests/business").with(csrf())

        // when
        val result = mockMvc.perform(request)

        // then
        result.andExpect(status().isMethodNotAllowed)
            .andExpect(header().string("Allow", containsString("GET")))
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
            .andExpect(jsonPath("$.message").value("지원하지 않는 HTTP 메서드입니다."))
    }

    @Test
    fun `지원하지 않는 요청 미디어 타입의 415와 Accept 헤더를 보존한다`() {
        // given
        val request = post("/api/v1/exception-tests/validation")
            .with(csrf())
            .contentType(MediaType.TEXT_PLAIN)
            .content("private-media-input")

        // when
        val result = mockMvc.perform(request)

        // then
        result.andExpect(status().isUnsupportedMediaType)
            .andExpect(header().string("Accept", containsString("application/json")))
            .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
    }

    @Test
    fun `응답 미디어 타입 협상 실패도 406을 보존하고 공통 JSON을 반환한다`() {
        // given
        val request = get("/api/v1/exception-tests/json-only").accept(MediaType.TEXT_PLAIN)

        // when
        val result = mockMvc.perform(request)

        // then
        result.andExpect(status().isNotAcceptable)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
    }

    @Test
    fun `없는 경로의 404를 보존하고 공통 JSON을 반환한다`() {
        // given
        val request = get("/api/v1/exception-tests/not-found")

        // when
        val result = mockMvc.perform(request)

        // then
        result.andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["/response-status", "/annotated-status"])
    fun `명시적인 HTTP 상태를 가진 예외를 500으로 바꾸지 않는다`(endpoint: String) {
        // given
        val request = get("/api/v1/exception-tests$endpoint")

        // when
        val result = mockMvc.perform(request)

        // then
        val response = result.andExpect(status().isGone)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"))
            .andReturn().response
        assertThat(response.contentAsString).doesNotContain("private-status-reason", "private-annotation-message")
    }

    @Test
    fun `예상하지 못한 오류는 내부 정보를 숨기고 메시지를 제외한 스택 트레이스를 기록한다`(output: CapturedOutput) {
        // given
        val request = get("/api/v1/exception-tests/unexpected")

        // when
        val result = mockMvc.perform(request)

        // then
        val response = result.andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
            .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
            .andReturn().response
        val json = objectMapper.readTree(response.contentAsString)
        assertThat(json.properties().map { it.key }).containsExactlyInAnyOrder("code", "message")
        assertThat(response.contentAsString).doesNotContain("private-server-input", "private-server-cause", "Exception")
        assertThat(output.all)
            .contains("ERROR", IllegalStateException::class.java.name)
            .containsPattern("""(?m)^\s*at\s+\S+""")
            .doesNotContain("private-server-input", "private-server-cause")
    }

    @Test
    fun `컨트롤러의 인가 예외는 Security 처리 경로로 전달하여 403을 유지한다`() {
        // given
        val request = get("/api/v1/exception-tests/access-denied")

        // when
        val result = mockMvc.perform(request)

        // then
        result.andExpect(status().isForbidden)
    }

    @Test
    fun `컨트롤러의 인증 예외는 Security 처리 경로로 전달하여 401을 유지한다`() {
        // given
        val request = get("/api/v1/exception-tests/authentication")

        // when
        val result = mockMvc.perform(request)

        // then
        result.andExpect(status().isUnauthorized)
    }

    @RestController
    class TestController {
        @GetMapping("/api/v1/exception-tests/business")
        fun business(): ErrorResponse = throw BusinessException(
            TestErrorCode.TEST_BUSINESS_ERROR,
            IllegalStateException("sensitive-cause"),
        )

        @PostMapping("/api/v1/exception-tests/validation", consumes = [MediaType.APPLICATION_JSON_VALUE])
        fun validation(@Valid @RequestBody request: TestRequest): String = request.name

        @PostMapping("/api/v1/exception-tests/multiple-field-validation")
        fun multipleFieldValidation(@Valid @RequestBody request: MultiFieldRequest): String = request.name

        @PostMapping("/api/v1/exception-tests/object-validation")
        fun objectValidation(@Valid @RequestBody request: ObjectRequest): String = request.name

        @InitBinder("objectRequest")
        fun configureObjectValidator(binder: WebDataBinder) {
            binder.addValidators(object : Validator {
                override fun supports(clazz: Class<*>): Boolean = clazz == ObjectRequest::class.java

                override fun validate(target: Any, errors: Errors) {
                    errors.reject("invalid")
                }
            })
        }

        @GetMapping("/api/v1/exception-tests/model")
        fun model(@Valid @ModelAttribute request: CountRequest): Int = request.count

        @PostMapping("/api/v1/exception-tests/map-validation")
        fun mapValidation(@Valid @RequestBody request: MapRequest): Int = request.profiles.size

        @GetMapping("/api/v1/exception-tests/query")
        fun query(@RequestParam("count") count: Int): Int = count

        @GetMapping("/api/v1/exception-tests/header")
        fun requiredHeader(@RequestHeader("X-Required-Header") value: String): String = value

        @GetMapping("/api/v1/exception-tests/method-validation")
        fun methodValidation(
            @RequestParam("page-size") @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다.") size: Int,
        ): Int = size

        @PostMapping("/api/v1/exception-tests/nested-method-validation")
        fun nestedMethodValidation(
            @RequestParam("count") @Min(value = 1, message = "개수는 1 이상이어야 합니다.") count: Int,
            @Valid @RequestBody request: NestedRequest,
        ): Int = count + request.profile.name.length

        @GetMapping("/api/v1/exception-tests/return-validation")
        @Size(max = 3)
        fun returnValidation(): String = "private-return-input"

        @GetMapping("/api/v1/exception-tests/json-only", produces = [MediaType.APPLICATION_JSON_VALUE])
        fun jsonOnly(): Map<String, String> = mapOf("result" to "ok")

        @GetMapping("/api/v1/exception-tests/response-status")
        fun responseStatus(): String = throw ResponseStatusException(HttpStatus.GONE, "private-status-reason")

        @GetMapping("/api/v1/exception-tests/annotated-status")
        fun annotatedStatus(): String = throw ExpiredRequestException()

        @GetMapping("/api/v1/exception-tests/unexpected")
        fun unexpected(): String = throw IllegalStateException(
            "private-server-input",
            IllegalArgumentException("private-server-cause"),
        )

        @GetMapping("/api/v1/exception-tests/access-denied")
        fun accessDenied(): String = throw AccessDeniedException("private-security-message")

        @GetMapping("/api/v1/exception-tests/authentication")
        fun authentication(): String = throw AuthenticationCredentialsNotFoundException("private-security-message")
    }

    @Validated
    @RestController
    class ValidatedController {
        @GetMapping("/api/v1/exception-tests/constraint-validation")
        fun constraintValidation(
            @RequestParam("count") @Min(value = 1, message = "개수는 1 이상이어야 합니다.") count: Int,
        ): Int = count

        @GetMapping("/api/v1/exception-tests/constraint-return-validation")
        @Size(max = 3)
        fun constraintReturnValidation(): String = "private-return-input"
    }

    data class TestRequest(
        @field:Size(max = 3, message = "이름은 3자 이하여야 합니다.")
        val name: String,
    )

    data class MultiFieldRequest(
        @field:Size(max = 3, message = "이름은 3자 이하여야 합니다.")
        val name: String,
        @field:Min(value = 1, message = "개수는 1 이상이어야 합니다.")
        val count: Int,
    )

    data class ObjectRequest(val name: String)

    data class CountRequest(val count: Int = 0)

    data class NestedRequest(@field:Valid val profile: TestRequest)

    data class MapRequest(@field:Valid val profiles: Map<String, TestRequest>)

    @ResponseStatus(HttpStatus.GONE)
    class ExpiredRequestException : RuntimeException("private-annotation-message")

    enum class TestErrorCode(
        override val status: HttpStatus,
        override val message: String,
    ) : ErrorCode {
        TEST_BUSINESS_ERROR(HttpStatus.CONFLICT, "테스트 비즈니스 오류입니다."),
    }
}
