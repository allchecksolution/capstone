package com.allcheck.driver.application.command

import com.allcheck.driver.application.command.DriverCommandResult
import com.allcheck.driver.domain.entity.command.DriverIdempotencyRecord
import com.allcheck.driver.exception.DriverErrorCode
import com.allcheck.driver.infrastructure.repository.command.DriverIdempotencyRepository
import com.allcheck.global.exception.BusinessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat

@Service
@Transactional(readOnly = true)
class DriverIdempotencyService(
    private val records: DriverIdempotencyRepository,
    private val mapper: JsonMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun <T> known(actor: String, scope: String, key: String, request: Any, type: Class<T>): DriverCommandResult<T>? {
        val record = records.findByActorIdAndOperationScopeAndKeyHash(actor, scope, hash(key.toByteArray())) ?: return null
        if (record.requestFingerprint != fingerprint(request)) throw BusinessException(DriverErrorCode.IDEMPOTENCY_KEY_REUSED)
        return DriverCommandResult(mapper.readValue(record.responseJson, type), true)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun <T : Any> execute(actor: String, scope: String, key: String, request: Any, type: Class<T>, action: () -> T): DriverCommandResult<T> {
        val digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(listOf(actor, scope, key)))
        if (!records.tryLock(ByteBuffer.wrap(digest).long)) throw BusinessException(DriverErrorCode.OPERATION_IN_PROGRESS)
        val previous = known(actor, scope, key, request, type)
        if (previous != null) return previous
        val body = action()
        records.save(DriverIdempotencyRecord(actor, scope, hash(key.toByteArray()), fingerprint(request), mapper.writeValueAsString(body), clock.instant()))
        return DriverCommandResult(body, false)
    }

    private fun fingerprint(request: Any): String = hash(mapper.writeValueAsBytes(request))
    private fun hash(value: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))
}
