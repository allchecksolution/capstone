package com.allcheck.operations.work.application

import com.allcheck.global.exception.BusinessException
import com.allcheck.global.exception.GlobalErrorCode
import com.allcheck.operations.PhotoPhase
import com.allcheck.operations.ReportContent
import com.allcheck.operations.exception.OperationsErrorCode
import com.allcheck.operations.validInput
import com.allcheck.operations.work.PhotoMetadata
import com.allcheck.operations.work.PhotoSnapshot
import com.allcheck.operations.work.ReportOperations
import com.allcheck.operations.work.ReportSnapshot
import com.allcheck.operations.work.RevisionSnapshot
import com.allcheck.operations.work.domain.entity.WorkPhoto
import com.allcheck.operations.work.domain.entity.WorkReport
import com.allcheck.operations.work.domain.entity.WorkReportRevision
import com.allcheck.operations.work.infrastructure.repository.WorkPhotoRepository
import com.allcheck.operations.work.infrastructure.repository.WorkReportRepository
import com.allcheck.operations.work.infrastructure.repository.WorkReportRevisionRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Service
@Transactional(readOnly = true)
class ReportService(private val reports: WorkReportRepository, private val revisions: WorkReportRevisionRepository, private val photos: WorkPhotoRepository, private val works: WorkService) : ReportOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun submit(workId: Long, driverId: Long, expected: Int, content: ReportContent, before: List<Long>, after: List<Long>, now: Instant): ReportSnapshot {
        val report = WorkReport(workId, driverId, content, before, after, now)
        val ids = before + after
        val uploaded = photos.findAllById(ids)
        validInput(uploaded.size == ids.size && uploaded.all { it.workId == workId && it.uploadedByDriverId == driverId && it.status == "UPLOADED" && it.phase == if (it.photoFileId in before) PhotoPhase.BEFORE else PhotoPhase.AFTER })
        works.complete(workId, driverId, expected, now)
        return snapshot(reports.saveAndFlush(report))
    }
    override fun get(reportId: Long) = snapshot(entity(reportId))
    override fun list(page: Int, size: Int) = reports.findAll(PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "reportId"))).content.map { snapshot(it) }
    override fun forWorks(workIds: List<Long>) = if (workIds.isEmpty()) emptyMap() else reports.findAllByWorkIdIn(workIds).associate { it.workId to checkNotNull(it.reportId) }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun revise(reportId: Long, adminId: Long, expected: Int, reason: String, description: String?, diagnosis: String?, cost: Long?, now: Instant): ReportSnapshot {
        validInput(reason.isNotBlank() && (description != null || diagnosis != null || cost != null))
        val report = entity(reportId)
        val before = report.latest
        val after = ReportContent(description ?: before.workDescription, diagnosis ?: before.diagnosis, cost ?: before.totalCost)
        report.revise(expected, after)
        revisions.saveAndFlush(WorkReportRevision(reportId, report.version, adminId, reason, before, after, now))
        return snapshot(report)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun reservePhoto(workId: Long, driverId: Long, metadata: PhotoMetadata, bucket: String, now: Instant): PhotoSnapshot {
        val checksum = try {
            Base64.getDecoder().decode(metadata.checksumBase64)
        } catch (_: IllegalArgumentException) {
            throw BusinessException(GlobalErrorCode.INVALID_INPUT_VALUE)
        }
        val photo = WorkPhoto(workId, driverId, metadata.phase, metadata.originalFilename, bucket, "operations/works/$workId/${UUID.randomUUID()}", metadata.contentType, metadata.sizeBytes, checksum, now)
        return photos.saveAndFlush(photo).snapshot()
    }
    override fun photo(photoId: Long) = photoEntity(photoId).snapshot()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun completePhoto(photoId: Long, now: Instant): PhotoSnapshot = photoEntity(photoId).apply { complete(now) }.snapshot()
    private fun entity(id: Long) = reports.findById(id).orElseThrow { BusinessException(OperationsErrorCode.OPERATIONS_NOT_FOUND) }
    private fun photoEntity(id: Long) = photos.findById(id).orElseThrow { BusinessException(OperationsErrorCode.OPERATIONS_NOT_FOUND) }
    private fun snapshot(report: WorkReport) = ReportSnapshot(checkNotNull(report.reportId), report.workId, report.submittedByDriverId, report.original, report.latest, report.beforePhotoIds(), report.afterPhotoIds(), report.version, report.submittedAt, revisions.findAllByReportIdOrderByVersion(checkNotNull(report.reportId)).map { RevisionSnapshot(checkNotNull(it.revisionId), it.version, it.adminId, it.reason, it.beforeContent, it.afterContent, it.revisedAt) })
    private fun WorkPhoto.snapshot() = PhotoSnapshot(checkNotNull(photoFileId), workId, uploadedByDriverId, phase, bucket, objectKey, contentType, sizeBytes, checksumBase64(), status, uploadedAt)
}
