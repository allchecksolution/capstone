package com.allcheck.operations.coordination.infrastructure.dispatch

import com.allcheck.driver.DispatchDriver
import com.allcheck.driver.DriverDispatchOperations
import com.allcheck.operations.CandidateContext
import com.allcheck.operations.DriverAvailability
import com.allcheck.operations.DriverLookupException
import com.allcheck.operations.OperationsDrivers
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.CannotCreateTransactionException

@Component
class DriverDispatchAdapter(private val drivers: DriverDispatchOperations) : OperationsDrivers {
    override fun candidates(context: CandidateContext): List<DriverAvailability> = try {
        drivers.candidates(context.workType.name, context.observedAt)
            .mapNotNull { it.forSite(context) }
    } catch (_: TransientDataAccessException) {
        throw DriverLookupException(retryable = true)
    } catch (_: RecoverableDataAccessException) {
        throw DriverLookupException(retryable = true)
    } catch (_: CannotCreateTransactionException) {
        throw DriverLookupException(retryable = true)
    }

    override fun lockForAcceptance(driverId: Long, context: CandidateContext): DriverAvailability? = drivers.lockCandidate(driverId, context.workType.name)?.forSite(context)

    private fun DispatchDriver.forSite(context: CandidateContext): DriverAvailability? {
        if (serviceAreas.none { AdministrativeArea.serves(it, context.site.area) }) return null
        return DriverAvailability(
            driverId, activated = true, ready = true, specialties = setOf(context.workType), servesSite = true,
            longitude, latitude, measuredAt, locationValidity,
        )
    }
}
