package com.allcheck.operations.coordination.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.operations.ActorRole
import com.allcheck.operations.OperationsActor
import com.allcheck.operations.ReportContent
import com.allcheck.operations.RequestStatus
import com.allcheck.operations.exception.OperationsErrorCode
import com.allcheck.operations.request.RequestOperations
import com.allcheck.operations.work.PhotoMetadata
import com.allcheck.operations.work.ReportOperations
import com.allcheck.operations.work.WorkOperations
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class OperationsReportTransactions(private val commands: OperationsCommands, private val requests: RequestOperations, private val works: WorkOperations, private val reports: ReportOperations, private val em: EntityManager) {
    @Transactional
    fun submit(actor: OperationsActor, workId: Long, key: String, expected: Int, content: ReportContent, before: List<Long>, after: List<Long>): CommandResult {
        val work = works.get(workId)
        own(actor.role == ActorRole.DRIVER && actor.id == work.driverId)
        return commands.execute(actor, "SUBMIT_REPORT", workId, key, listOf(expected, content, before, after)) {
            current(work.requestId, workId)
            val now = commands.now()
            val report = reports.submit(workId, actor.id, expected, content, before, after, now)
            requests.complete(work.requestId, workId, now)
            CommandOutcome(report.reportId, work.requestId, "WORK_DONE", works.get(workId).version, workId, now)
        }
    }

    @Transactional
    fun revise(actor: OperationsActor, reportId: Long, key: String, expected: Int, reason: String, description: String?, diagnosis: String?, cost: Long?): CommandResult {
        own(actor.role == ActorRole.ADMIN)
        val report = reports.get(reportId)
        val work = works.get(report.workId)
        return commands.execute(actor, "REVISE_REPORT", reportId, key, listOf(expected, reason, description, diagnosis, cost)) {
            em.clear()
            requests.lock(work.requestId)
            val now = commands.now()
            val revised = reports.revise(reportId, actor.id, expected, reason, description, diagnosis, cost, now)
            CommandOutcome(reportId, work.requestId, "REVISED", revised.version, report.workId, now)
        }
    }

    @Transactional
    fun reserve(actor: OperationsActor, workId: Long, key: String, metadata: PhotoMetadata, bucket: String): CommandResult {
        val work = works.get(workId)
        own(actor.role == ActorRole.DRIVER && actor.id == work.driverId)
        return commands.execute(actor, "RESERVE_PHOTO", workId, key, metadata) {
            current(work.requestId, workId)
            val now = commands.now()
            val photo = reports.reservePhoto(workId, actor.id, metadata, bucket, now)
            CommandOutcome(photo.photoId, work.requestId, "PENDING", works.get(workId).version, workId, now)
        }
    }

    @Transactional
    fun completePhoto(actor: OperationsActor, workId: Long, photoId: Long, key: String): CommandResult {
        val work = works.get(workId)
        own(actor.role == ActorRole.DRIVER && actor.id == work.driverId)
        val photo = reports.photo(photoId)
        own(photo.workId == workId && photo.driverId == actor.id)
        return commands.execute(actor, "COMPLETE_PHOTO", photoId, key, workId) {
            // 완료 사진은 새 키에서도 첫 업로드 완료 시각을 유지한다.
            em.clear()
            requests.lock(work.requestId)
            val saved = reports.photo(photoId)
            if (saved.status != "UPLOADED") {
                state(requests.get(work.requestId).currentWorkId == workId && requests.get(work.requestId).status == RequestStatus.ACCEPTED)
                reports.completePhoto(photoId, commands.now())
            }
            CommandOutcome(photoId, work.requestId, "UPLOADED", works.get(workId).version, workId, checkNotNull(reports.photo(photoId).uploadedAt))
        }
    }
    private fun current(requestId: Long, workId: Long) {
        em.clear()
        val request = requests.lock(requestId)
        state(request.status == RequestStatus.ACCEPTED && request.currentWorkId == workId && works.get(workId).unfinished())
    }
    private fun own(value: Boolean) {
        if (!value) throw BusinessException(OperationsErrorCode.OPERATIONS_NOT_FOUND)
    }
    private fun state(value: Boolean) {
        if (!value) throw BusinessException(GlobalErrorCode.VERSION_CONFLICT)
    }
}
