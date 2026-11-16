package com.allcheck.reception.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.domain.entity.ReceptionCommandReceipt
import com.allcheck.reception.domain.vo.ReceptionConfirmationData
import com.allcheck.reception.domain.vo.ReceptionCreationData
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.exception.ReceptionErrorCode
import com.allcheck.reception.infrastructure.repository.ReceptionCommandReceiptRepository
import com.allcheck.reception.infrastructure.repository.ReceptionRepository
import com.allcheck.reception.presentation.dto.response.ReceptionConfirmedResponse
import com.allcheck.reception.presentation.dto.response.ReceptionDetailResponse
import com.allcheck.reception.presentation.dto.response.ReceptionReopenedResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock

/** POST 명령의 성공 결과와 업무 변경을 함께 커밋한다. 서명 URL을 만드는 명령은 지원하지 않는다. */
@Service
@Transactional(readOnly = true)
class ReceptionCommandService(
    private val service: ReceptionService,
    private val receipts: ReceptionCommandReceiptRepository,
    private val receptions: ReceptionRepository,
    private val clock: Clock,
) {
    private val mapper = JsonMapper.builder().findAndAddModules()
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DateTimeFeature.WRITE_DATES_WITH_CONTEXT_TIME_ZONE)
        .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        .build()

    @Transactional
    fun create(adminId: Long, key: String, data: ReceptionCreationData): ReceptionCommandResult<ReceptionDetailResponse> = execute(adminId, "CREATE", 0, key, data, ReceptionDetailResponse::class.java) {
        val result = service.create(adminId, data)
        ReceptionExternalId.RECEPTION.parse(result.receptionId) to result
    }

    @Transactional
    fun confirm(adminId: Long, receptionId: Long, key: String, expectedVersion: Int, data: ReceptionConfirmationData): ReceptionCommandResult<ReceptionConfirmedResponse> = execute(adminId, "CONFIRM", receptionId, key, mapOf("expectedVersion" to expectedVersion, "finalData" to data), ReceptionConfirmedResponse::class.java) {
        receptionId to service.confirm(adminId, receptionId, expectedVersion, data)
    }

    @Transactional
    fun reopen(adminId: Long, receptionId: Long, key: String, expectedVersion: Int): ReceptionCommandResult<ReceptionReopenedResponse> = execute(adminId, "REOPEN", receptionId, key, mapOf("expectedVersion" to expectedVersion), ReceptionReopenedResponse::class.java) {
        receptionId to service.reopen(adminId, receptionId, expectedVersion)
    }

    private fun <T : Any> execute(
        adminId: Long,
        operation: String,
        scope: Long,
        key: String,
        request: Any,
        resultType: Class<T>,
        action: () -> Pair<Long, T>,
    ): ReceptionCommandResult<T> {
        if (key.isBlank() || key.codePointCount(0, key.length) > 200) throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
        if (scope != 0L) ensureOwned(adminId, scope)
        val lockHash = digest("ALLCHECK:RECEPTION:$adminId:$operation:$scope:$key".toByteArray(Charsets.UTF_8))
        val locked = receipts.tryTransactionLock(ByteBuffer.wrap(lockHash).long)
        if (locked != true) throw BusinessException(GlobalErrorCode.OPERATION_IN_PROGRESS)
        val hash = digest(mapper.writeValueAsBytes(request))
        val receipt = receipts.findByActorAdminIdAndOperationAndScopeReceptionIdAndIdempotencyKey(adminId, operation, scope, key)
        if (receipt != null) {
            if (!receipt.matches(hash)) throw BusinessException(GlobalErrorCode.IDEMPOTENCY_KEY_REUSED)
            ensureOwned(adminId, receipt.resultReceptionId)
            return ReceptionCommandResult(mapper.convertValue(receipt.responseBody, resultType), true)
        }
        val (receptionId, value) = action()
        ensureOwned(adminId, receptionId)
        val body = mapper.readValue(mapper.writeValueAsBytes(value), object : TypeReference<Map<String, Any?>>() {})
        receipts.saveAndFlush(ReceptionCommandReceipt(adminId, operation, scope, key, hash, receptionId, body, clock.instant()))
        return ReceptionCommandResult(value, false)
    }

    private fun ensureOwned(adminId: Long, receptionId: Long) {
        if (receptions.findByReceptionIdAndOwnerAdminId(receptionId, adminId) == null) throw BusinessException(ReceptionErrorCode.RECEPTION_NOT_FOUND)
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

data class ReceptionCommandResult<T>(val value: T, val replayed: Boolean)
