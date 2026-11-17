package com.allcheck.driver.application

import com.allcheck.driver.domain.vo.DriverAccountStatus
import com.allcheck.driver.exception.DriverErrorCode
import com.allcheck.driver.infrastructure.repository.DriverHistoryRepository
import com.allcheck.driver.infrastructure.repository.DriverRepository
import com.allcheck.driver.presentation.dto.response.BusinessVerificationResponse
import com.allcheck.driver.presentation.dto.response.DriverDetailResponse
import com.allcheck.driver.presentation.dto.response.DriverListResponse
import com.allcheck.driver.presentation.dto.response.DriverPageInfo
import com.allcheck.driver.presentation.dto.response.DriverRegistrationView
import com.allcheck.driver.presentation.dto.response.DriverSummary
import com.allcheck.driver.presentation.dto.response.RegistrationRejection
import com.allcheck.global.exception.BusinessException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Locale

@Service
@Transactional(readOnly = true)
class DriverQueryService(
    private val drivers: DriverRepository,
    private val history: DriverHistoryRepository,
) {
    fun checkCanSubmit(accountId: String) {
        if (drivers.existsByAccountId(accountId)) throw BusinessException(DriverErrorCode.DRIVER_ALREADY_REGISTERED)
    }

    fun checkCanResubmit(accountId: String, expectedVersion: Long) {
        val driver = drivers.findByAccountId(accountId) ?: throw BusinessException(DriverErrorCode.DRIVER_NOT_FOUND)
        driver.checkVersion(expectedVersion)
        driver.requireRejected()
    }

    fun mine(accountId: String): DriverRegistrationView = (drivers.findByAccountId(accountId) ?: throw BusinessException(DriverErrorCode.DRIVER_NOT_FOUND)).view()

    fun list(status: DriverAccountStatus?, query: String?, page: Int, size: Int): DriverListResponse {
        val pattern = query?.lowercase(Locale.ROOT)?.replace("!", "!!")?.replace("%", "!%")?.replace("_", "!_")?.let { "%$it%" } ?: ""
        val result = drivers.search(status, pattern, PageRequest.of(page - 1, size))
        return DriverListResponse(
            result.content.map { DriverSummary(it.id, it.profile.workerName, it.business.businessName, it.accountStatus, it.submittedAt, it.version) },
            DriverPageInfo(page, size, result.totalElements, result.totalPages),
        )
    }

    fun detail(driverId: String): DriverDetailResponse {
        val driver = drivers.findById(driverId).orElseThrow { BusinessException(DriverErrorCode.DRIVER_NOT_FOUND) }
        return DriverDetailResponse(
            driver.id,
            driver.accountStatus,
            driver.version,
            driver.submittedAt,
            driver.profile.response(maskAccount = true),
            driver.business.response(),
            BusinessVerificationResponse(verifiedAt = driver.business.verifiedAt),
            history.findAllByDriverIdAndEventTypeOrderByOccurredAtAsc(driverId, "REJECTED")
                .map { RegistrationRejection(checkNotNull(it.reason), it.occurredAt) },
        )
    }
}
