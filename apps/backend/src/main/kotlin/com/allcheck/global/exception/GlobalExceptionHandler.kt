package com.allcheck.global.exception

import jakarta.validation.ConstraintViolationException
import jakarta.validation.ElementKind
import jakarta.validation.Path
import org.slf4j.LoggerFactory
import org.springframework.core.MethodParameter
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.validation.FieldError
import org.springframework.validation.ObjectError
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import org.springframework.web.ErrorResponse as SpringErrorResponse

/**
 * Spring MVC 전역 예외 처리기.
 *
 * ResponseEntityExceptionHandler를 상속하여 Spring MVC의 기본 예외 처리 흐름을 활용한다.
 * 부모 클래스가 예외 유형에 따라 적절한 처리 메서드를 호출하며,
 * handleExceptionInternal을 재정의하여 HTTP 상태와 응답 헤더는 유지하면서
 * 응답 본문을 프로젝트 공통 ErrorResponse 형식으로 통일한다.
 *
 * BusinessException은 별도 핸들러에서 ErrorCode에 정의된 상태와 메시지로 처리한다.
 *
 * 인증·인가 예외는 Security의 AuthenticationEntryPoint 및 AccessDeniedHandler에서 처리하며,
 * 향후 멱등성 관련 오류는 BusinessException을 통해 공통 처리한다.
 */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /**
     * 비즈니스 예외를 ErrorCode에 선언된 HTTP 상태와 공개 메시지로 변환한다.
     * 원본 예외 메시지나 cause 대신 오류 코드만 로그에 기록하고 JSON 응답을 직접 생성한다.
     */
    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(exception: BusinessException): ResponseEntity<ErrorResponse> {
        val errorCode = exception.errorCode
        log.info("Business exception: code={}", errorCode.name)

        return ResponseEntity.status(errorCode.status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ErrorResponse.from(errorCode))
    }

    /**
     * 클래스 수준 @Validated를 통한 AOP 검증 등에서 발생하는 Bean Validation 예외를 처리한다.
     * 반환값 제약 위반은 500으로, 그 외 제약 위반은 검증 상세를 포함한 400으로 변환한다.
     */
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        exception: ConstraintViolationException,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        // 위반 경로에 반환값 노드가 하나라도 있으면 서버의 반환 계약 위반으로 분류한다.
        val returnValueViolation = exception.constraintViolations.any { violation ->
            violation.propertyPath.any { it.kind == ElementKind.RETURN_VALUE }
        }
        if (returnValueViolation) {
            return handleExceptionInternal(exception, null, HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request)
        }

        // 각 위반을 필드 경로와 메시지로 변환하고, 필드명으로 정렬해 응답 순서를 안정화한다.
        val details = exception.constraintViolations.map { violation ->
            ValidationError(
                field = validationField(violation.propertyPath),
                reason = violation.message.ifBlank { GlobalErrorCode.INVALID_INPUT_VALUE.message },
            )
        }.sortedBy { it.field }
        val body = ErrorResponse.from(GlobalErrorCode.INVALID_INPUT_VALUE, details)
        return handleExceptionInternal(exception, body, HttpHeaders(), HttpStatus.BAD_REQUEST, request)
    }

    /**
     * @Valid가 적용된 요청 DTO 등의 바인딩·검증 실패를 처리한다.
     * 필드 오류와 객체 단위 오류를 모두 검증 상세로 변환하고 Spring이 전달한 상태와 헤더를 유지한다.
     */
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val details = ex.bindingResult.allErrors.map { validationError(it) }
        val body = ErrorResponse.from(GlobalErrorCode.INVALID_INPUT_VALUE, details)
        return handleExceptionInternal(ex, body, headers, status, request)
    }

    /**
     * 컨트롤러 인자나 반환값에 직접 선언된 제약 조건을 Spring MVC가 검증하다 발생한 예외를 처리한다.
     * 인자별 검증 결과에는 @Valid로 검사한 중첩 DTO의 오류도 포함될 수 있다.
     */
    override fun handleHandlerMethodValidationException(
        ex: HandlerMethodValidationException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        // 반환값 검증 실패는 서버 오류이므로 검증 상세를 노출하지 않고 Spring이 정한 상태를 유지한다.
        if (ex.isForReturnValue) {
            return handleExceptionInternal(ex, null, headers, status, request)
        }

        // 인자별 오류 목록을 하나로 합친다. 객체 오류는 필드 경로를, 단순 인자 오류는 실제 요청 이름을 사용한다.
        val parameterErrors = ex.parameterValidationResults.flatMap { result ->
            result.resolvableErrors.map { error ->
                if (error is ObjectError) {
                    validationError(error)
                } else {
                    ValidationError(
                        field = requestParameterName(result.methodParameter),
                        reason = error.defaultMessage.orEmpty().ifBlank { GlobalErrorCode.INVALID_INPUT_VALUE.message },
                    )
                }
            }
        }
        // 여러 인자의 조합에 대한 제약 위반은 하나의 필드를 지정할 수 없어 요청 전체의 오류로 표시한다.
        val crossParameterErrors = ex.crossParameterValidationResults.map { error ->
            ValidationError(
                field = "request",
                reason = error.defaultMessage.orEmpty().ifBlank { GlobalErrorCode.INVALID_INPUT_VALUE.message },
            )
        }
        val body = ErrorResponse.from(GlobalErrorCode.INVALID_INPUT_VALUE, parameterErrors + crossParameterErrors)
        return handleExceptionInternal(ex, body, headers, status, request)
    }

    /**
     * 지원하지 않는 HTTP 메서드에 대해 405 상태와 허용 메서드를 담은 Allow 헤더를 유지한다.
     * 부모의 별도 경고 로그 단계를 거치지 않고 공통 처리로 전달해 중복 로깅을 피한다.
     */
    override fun handleHttpRequestMethodNotSupported(
        ex: HttpRequestMethodNotSupportedException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? = handleExceptionInternal(ex, null, headers, status, request)

    /**
     * 다른 예외 처리 메서드가 담당하지 않는 Exception의 최종 처리 경로다.
     * 예외에 명시된 HTTP 상태가 있으면 보존하고, 없으면 500 응답으로 변환한다.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(exception: Exception, request: WebRequest): ResponseEntity<Any>? {
        // 향후 메서드 보안에서 발생하는 예외도 Security의 인증·인가 처리 경로로 전달한다.
        if (exception is AuthenticationException || exception is AccessDeniedException) {
            throw exception
        }

        // SpringErrorResponse는 프로젝트 DTO와 다른 Spring의 예외 응답 인터페이스로, 상태와 헤더를 제공한다.
        val frameworkError = exception as? SpringErrorResponse
        val responseStatus = AnnotatedElementUtils.findMergedAnnotation(exception.javaClass, ResponseStatus::class.java)
        // Spring 인터페이스의 상태, 예외 클래스의 @ResponseStatus, 기본 500 순서로 선택한다.
        val status = frameworkError?.statusCode ?: responseStatus?.code ?: HttpStatus.INTERNAL_SERVER_ERROR
        return handleExceptionInternal(exception, null, frameworkError?.headers ?: HttpHeaders(), status, request)
    }

    /**
     * 이 메서드로 전달된 예외의 응답 바디, 로그, Content-Type을 공통 정책에 맞춘다.
     * 반환 타입의 Any는 부모 메서드의 시그니처이며, 실제로 전달하는 바디는 프로젝트의 ErrorResponse다.
     * nullable 반환값은 이미 커밋된 응답처럼 새 응답을 작성할 수 없는 상황을 허용한다.
     */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        // 이미 전송을 시작한 응답의 상태·헤더·바디를 새 오류 응답으로 덮어쓰지 않는다.
        if ((request as? ServletWebRequest)?.response?.isCommitted == true) {
            return null
        }

        // 필수 RequestParam/RequestHeader 누락, 타입 변환 실패, 잘못된 JSON 등도 여기서 통일한다.
        // 전용 처리 메서드가 만든 ErrorResponse는 유지하고, null이나 Spring의 기본 바디는 공통 DTO로 교체한다.
        val response = body as? ErrorResponse ?: ErrorResponse.from(globalErrorCode(statusCode))
        // 서버 오류를 우선 분류하고, 나머지는 검증 상세 유무에 따라 필요한 정보만 로그에 기록한다.
        when {
            statusCode.is5xxServerError -> logServerError(ex)
            response.details != null -> logValidationFailure(response.details)
            else -> log.warn("Request failed: status={}, exception={}", statusCode.value(), ex.javaClass.simpleName)
        }

        // 전달받은 헤더를 직접 수정하지 않고 복사해 Allow, Accept 등 예외별 응답 헤더를 보존한다.
        val responseHeaders = HttpHeaders()
        responseHeaders.putAll(headers)
        // Accept 조건을 충족하지 못한 406 응답도 공통 JSON 형식으로 전달한다.
        responseHeaders.contentType = MediaType.APPLICATION_JSON
        // 바디를 확정해 전달하므로 부모가 기본 ProblemDetail로 채우지 않고 ResponseEntity를 생성한다.
        return super.handleExceptionInternal(ex, response, responseHeaders, statusCode, request)
    }

    /**
     * HTTP 상태에 대응하는 공통 응답 코드를 선택한다.
     * 이 매핑은 바디의 code와 message만 결정하며 실제 HTTP 상태는 변경하지 않는다.
     */
    private fun globalErrorCode(status: HttpStatusCode): GlobalErrorCode = when {
        status == HttpStatus.METHOD_NOT_ALLOWED -> GlobalErrorCode.METHOD_NOT_ALLOWED
        // 전용 코드가 없는 404·406·415 등은 공통 요청 오류 코드를 사용하되 HTTP 상태는 유지한다.
        status.is4xxClientError -> GlobalErrorCode.INVALID_INPUT_VALUE
        else -> GlobalErrorCode.INTERNAL_SERVER_ERROR
    }

    /**
     * Spring의 검증 오류를 응답용 필드·사유 쌍으로 변환한다.
     * 필드가 없는 객체 단위 오류는 request로 표시하고, 비어 있는 메시지는 공통 메시지로 대체한다.
     */
    private fun validationError(error: ObjectError): ValidationError {
        val fieldError = error as? FieldError
        // 바인딩 오류의 기본 메시지에는 입력값이나 내부 타입 이름이 포함될 수 있다.
        val reason = if (fieldError?.isBindingFailure == true) {
            GlobalErrorCode.INVALID_INPUT_VALUE.message
        } else {
            error.defaultMessage.orEmpty().ifBlank { GlobalErrorCode.INVALID_INPUT_VALUE.message }
        }
        return ValidationError(field = fieldError?.field ?: "request", reason = reason)
    }

    /**
     * Bean Validation 경로에서 파라미터·프로퍼티 이름만 골라 점으로 연결한다.
     * 메서드명 등 다른 종류의 노드는 제외하며, 추출할 이름이 없으면 request를 사용한다.
     */
    private fun validationField(path: Path): String = path
        .filter { it.kind == ElementKind.PROPERTY || it.kind == ElementKind.PARAMETER }
        .mapNotNull { it.name }
        .joinToString(".")
        .ifBlank { "request" }

    /**
     * RequestParam·RequestHeader·PathVariable에 지정된 외부 요청 이름을 우선 사용한다.
     * 예를 들어 @RequestParam("page-size") size의 오류 필드는 size가 아닌 page-size가 된다.
     * 애너테이션 이름이 비어 있으면 메서드 파라미터명, 그마저 없으면 request로 대체한다.
     */
    private fun requestParameterName(parameter: MethodParameter): String {
        val annotationName = parameter.getParameterAnnotation(RequestParam::class.java)?.let { it.name.ifBlank { it.value } }
            ?: parameter.getParameterAnnotation(RequestHeader::class.java)?.let { it.name.ifBlank { it.value } }
            ?: parameter.getParameterAnnotation(PathVariable::class.java)?.let { it.name.ifBlank { it.value } }
        return annotationName?.takeIf { it.isNotBlank() } ?: parameter.parameterName ?: "request"
    }

    /**
     * 검증 실패의 필드 목록과 전체 오류 개수를 WARN으로 기록한다.
     * 중복 필드명은 합치고 검증 메시지나 입력값은 기록하지 않는다.
     */
    private fun logValidationFailure(details: List<ValidationError>) {
        // Map 키 등 사용자 입력이 포함될 수 있는 경로 부분은 로그에서 제외한다.
        val fields = details.map { it.field.substringBefore('[') }.distinct()
        log.warn("Request validation failed: fields={}, errorCount={}", fields, details.size)
    }

    /**
     * 원본 예외의 타입명과 스택 위치만 담은 별도 Throwable을 만들어 ERROR로 기록한다.
     * 원본 메시지·cause·suppressed 예외는 복사하지 않으므로 내부 원인의 상세 정보도 로그에서 제외된다.
     */
    private fun logServerError(exception: Exception) {
        // 메시지와 cause의 입력값 노출을 막고 예외 타입과 실제 스택 위치만 기록한다.
        val safeException = Throwable(exception.javaClass.name)
        safeException.stackTrace = exception.stackTrace
        log.error("Server error", safeException)
    }
}
