package com.allcheck.operations.coordination.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.operations.ActorRole
import com.allcheck.operations.OperationsActor
import com.allcheck.operations.OperationsGeocoder
import com.allcheck.operations.dispatch.DispatchOperations
import com.allcheck.operations.exception.OperationsErrorCode
import com.allcheck.reception.ReceptionOperations
import org.springframework.stereotype.Service

/** 원격 조회는 쓰기 트랜잭션에 들어가기 전에 마친다. 저장 시점에는 상태/버전/유효시간을 재확인한다. */
@Service
class OperationsService(
    private val receptions: ReceptionOperations,
    private val geocoder: OperationsGeocoder,
    private val commands: OperationsCommands,
    private val transactions: OperationsTransactions,
    private val dispatch: DispatchOperations,
) {
    fun register(actor: OperationsActor, receptionId: Long, version: Int, key: String): CommandResult {
        if (actor.role != ActorRole.ADMIN) throw BusinessException(OperationsErrorCode.OPERATIONS_NOT_FOUND)
        commands.replay(actor, "REGISTER", receptionId, key, version)?.let { return it }
        val source = receptions.confirmedSnapshot(actor.id, receptionId, version)
        val site = geocoder.resolve(source.address)
        return transactions.register(actor, receptionId, version, key, source, site)
    }
    fun respond(actor: OperationsActor, offerId: Long, key: String, accept: Boolean): CommandResult {
        val offer = dispatch.offer(offerId)
        if (actor.role != ActorRole.DRIVER || actor.id != offer.driverId) throw BusinessException(OperationsErrorCode.OPERATIONS_NOT_FOUND)
        commands.replay(actor, "RESPOND", offerId, key, accept)?.let { return it }
        return transactions.respond(actor, offerId, key, accept)
    }
}
