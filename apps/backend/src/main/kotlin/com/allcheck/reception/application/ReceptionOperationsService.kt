package com.allcheck.reception.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.ReceptionOperations
import com.allcheck.reception.ReceptionSnapshot
import com.allcheck.reception.domain.entity.Reception
import com.allcheck.reception.exception.ReceptionErrorCode
import com.allcheck.reception.infrastructure.repository.ReceptionRepository
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional(readOnly = true)
class ReceptionOperationsService(private val receptions: ReceptionRepository, private val clock: Clock) : ReceptionOperations {
    override fun confirmedSnapshot(adminId: Long, receptionId: Long, expectedVersion: Int): ReceptionSnapshot = snapshot(owned(adminId, receptionId), expectedVersion)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun registerRequest(adminId: Long, receptionId: Long, expectedVersion: Int, requestId: Long): ReceptionSnapshot {
        val reception = owned(adminId, receptionId)
        val confirmed = snapshot(reception, expectedVersion)
        reception.registerRequest(expectedVersion, requestId, clock.instant())
        try {
            receptions.flush()
        } catch (exception: OptimisticLockingFailureException) {
            throw BusinessException(GlobalErrorCode.VERSION_CONFLICT, exception)
        }
        return confirmed
    }

    private fun owned(adminId: Long, receptionId: Long) = receptions.findByReceptionIdAndOwnerAdminId(receptionId, adminId)
        ?: throw BusinessException(ReceptionErrorCode.RECEPTION_NOT_FOUND)

    private fun snapshot(reception: Reception, expectedVersion: Int): ReceptionSnapshot {
        val data = reception.confirmedData(expectedVersion)
        return ReceptionSnapshot(
            checkNotNull(reception.receptionId), expectedVersion, checkNotNull(data.workType).name,
            checkNotNull(data.symptoms), checkNotNull(data.address), checkNotNull(data.desiredVisitFrom),
            checkNotNull(data.desiredVisitTo), checkNotNull(data.contactPhone), data.notes, checkNotNull(reception.confirmedAt),
        )
    }
}
