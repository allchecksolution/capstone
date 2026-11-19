package com.allcheck.operations.coordination.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.operations.OperationsActor
import com.allcheck.operations.domain.entity.OperationsReceipt
import com.allcheck.operations.infrastructure.repository.OperationsReceiptRepository
import com.allcheck.operations.validInput
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

data class CommandOutcome(val resourceId: Long, val requestId: Long, val status: String, val version: Int, val linkedId: Long? = null, val at: Instant)
data class CommandResult(val value: CommandOutcome, val replayed: Boolean)

/** 호출자는 매번 현재 접근 권한을 확인한다. receipt에는 URL이나 업로드 토큰을 넣지 않는다. */
@Service
@Transactional(readOnly = true)
class OperationsCommands(private val receipts: OperationsReceiptRepository, private val clock: Clock) {
    private val mapper = JsonMapper.builder().findAndAddModules().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build()

    fun replay(actor: OperationsActor, operation: String, scope: Long, key: String, input: Any): CommandResult? {
        validateKey(key)
        val receipt = receipts.findByActorRoleAndActorIdAndOperationAndScopeIdAndIdempotencyKey(actor.role.name, actor.id, operation, scope, key) ?: return null
        if (!MessageDigest.isEqual(receipt.fingerprint, hash(mapper.writeValueAsBytes(input)))) throw BusinessException(GlobalErrorCode.IDEMPOTENCY_KEY_REUSED)
        return CommandResult(mapper.convertValue(receipt.result, CommandOutcome::class.java), true)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun execute(actor: OperationsActor, operation: String, scope: Long, key: String, input: Any, action: () -> CommandOutcome): CommandResult {
        validateKey(key)
        lock("command:${actor.role}:${actor.id}:$operation:$scope:$key")
        replay(actor, operation, scope, key, input)?.let { return it }
        val outcome = action()
        val body = mapper.convertValue(outcome, object : TypeReference<Map<String, Any?>>() {})
        receipts.saveAndFlush(OperationsReceipt(actor.role.name, actor.id, operation, scope, key, hash(mapper.writeValueAsBytes(input)), body, now()))
        return CommandResult(outcome, false)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun driverLock(driverId: Long) = lock("driver:$driverId")
    fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
    private fun validateKey(key: String) = validInput(key.isNotBlank() && key.codePointCount(0, key.length) <= 200)
    private fun lock(scope: String) {
        if (!receipts.tryLock(ByteBuffer.wrap(hash("ALLCHECK:OPERATIONS:$scope".toByteArray())).long)) throw BusinessException(GlobalErrorCode.OPERATION_IN_PROGRESS)
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
