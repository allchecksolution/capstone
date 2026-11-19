package com.allcheck.operations.work

import com.allcheck.operations.PhotoPhase
import com.allcheck.operations.ReportContent
import java.time.Instant

interface ReportOperations {
    fun submit(workId: Long, driverId: Long, expected: Int, content: ReportContent, before: List<Long>, after: List<Long>, now: Instant): ReportSnapshot
    fun get(reportId: Long): ReportSnapshot
    fun list(page: Int, size: Int): List<ReportSnapshot>
    fun forWorks(workIds: List<Long>): Map<Long, Long>
    fun revise(reportId: Long, adminId: Long, expected: Int, reason: String, description: String?, diagnosis: String?, cost: Long?, now: Instant): ReportSnapshot
    fun reservePhoto(workId: Long, driverId: Long, metadata: PhotoMetadata, bucket: String, now: Instant): PhotoSnapshot
    fun photo(photoId: Long): PhotoSnapshot
    fun completePhoto(photoId: Long, now: Instant): PhotoSnapshot
}
data class ReportSnapshot(val reportId: Long, val workId: Long, val submittedByDriverId: Long, val original: ReportContent, val latest: ReportContent, val beforePhotoIds: List<Long>, val afterPhotoIds: List<Long>, val version: Int, val submittedAt: Instant, val revisions: List<RevisionSnapshot>)
data class RevisionSnapshot(val revisionId: Long, val version: Int, val adminId: Long, val reason: String, val before: ReportContent, val after: ReportContent, val revisedAt: Instant)
data class PhotoMetadata(val phase: PhotoPhase, val originalFilename: String, val contentType: String, val sizeBytes: Long, val checksumBase64: String)
data class PhotoSnapshot(val photoId: Long, val workId: Long, val driverId: Long, val phase: PhotoPhase, val bucket: String, val objectKey: String, val contentType: String, val sizeBytes: Long, val checksumBase64: String, val status: String, val uploadedAt: Instant?)
