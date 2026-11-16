package com.allcheck.reception.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.reception.domain.entity.Reception
import com.allcheck.reception.domain.vo.ReceptionAnalysisStatus
import com.allcheck.reception.domain.vo.ReceptionConfirmationData
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.exception.ReceptionErrorCode
import com.allcheck.reception.infrastructure.repository.ReceptionAnalysisRepository
import com.allcheck.reception.infrastructure.repository.ReceptionRepository
import com.allcheck.reception.presentation.dto.response.ReceptionConfirmedResponse
import com.allcheck.reception.presentation.dto.response.ReceptionFieldsResponse
import com.allcheck.reception.presentation.dto.response.ReceptionReopenedResponse
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** 호출자는 인증 문맥에서 얻은 관리자 ID를 전달한다. 외부 Request DTO나 클라이언트의 분석 상태를 받지 않는다. */
@Service
@Transactional(readOnly = true)
class ReceptionService(
    private val receptionRepository: ReceptionRepository,
    private val analysisRepository: ReceptionAnalysisRepository,
    private val clock: Clock,
) {
    @Transactional
    fun confirm(
        adminId: Long,
        receptionId: Long,
        expectedVersion: Int,
        data: ReceptionConfirmationData,
    ): ReceptionConfirmedResponse {
        val reception = findOwnedReception(adminId, receptionId)
        val analysisStatus = currentAnalysisStatus(reception)

        reception.confirm(expectedVersion, data, analysisStatus, clock.instant())
        flushChanges()

        return ReceptionConfirmedResponse(
            receptionId = ReceptionExternalId.RECEPTION.format(checkNotNull(reception.receptionId)),
            reviewStatus = reception.reviewStatus,
            version = reception.version,
            confirmedAt = checkNotNull(reception.confirmedAt),
            finalData = ReceptionFieldsResponse.from(reception.finalData),
        )
    }

    @Transactional
    fun reopen(adminId: Long, receptionId: Long, expectedVersion: Int): ReceptionReopenedResponse {
        val reception = findOwnedReception(adminId, receptionId)

        reception.reopen(expectedVersion, clock.instant())
        flushChanges()

        return ReceptionReopenedResponse(
            receptionId = ReceptionExternalId.RECEPTION.format(checkNotNull(reception.receptionId)),
            reviewStatus = reception.reviewStatus,
            version = reception.version,
            updatedAt = reception.updatedAt,
            confirmedAt = reception.confirmedAt,
            finalData = ReceptionFieldsResponse.from(reception.finalData),
        )
    }

    private fun findOwnedReception(adminId: Long, receptionId: Long): Reception = receptionRepository
        .findByReceptionIdAndOwnerAdminId(receptionId, adminId)
        ?: throw BusinessException(ReceptionErrorCode.RECEPTION_NOT_FOUND)

    private fun currentAnalysisStatus(reception: Reception): ReceptionAnalysisStatus? {
        val analysisId = reception.latestAnalysisRequestId ?: return null
        val analysis = checkNotNull(
            analysisRepository.findByAnalysisRequestIdAndReceptionId(analysisId, checkNotNull(reception.receptionId)),
        ) { "The current reception analysis must exist." }
        check(analysis.requestedByAdminId == reception.ownerAdminId && analysis.recordingFileId == reception.recordingFileId) {
            "The current analysis must belong to the reception owner and recording."
        }
        return analysis.status
    }

    /** 응답을 만들기 전에 경합을 감지하고 실패한 트랜잭션 전체를 롤백한다. 자동 재시도는 하지 않는다. */
    private fun flushChanges() {
        try {
            receptionRepository.flush()
        } catch (exception: OptimisticLockingFailureException) {
            throw BusinessException(GlobalErrorCode.VERSION_CONFLICT, exception)
        }
    }
}
