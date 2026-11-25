package com.allcheck.operations.work.domain.entity

import com.allcheck.operations.ReportContent
import com.allcheck.operations.expectedVersion
import com.allcheck.operations.validInput
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "work_reports")
class WorkReport(
    @field:Column(nullable = false, unique = true, updatable = false) val workId: Long,
    @field:Column(nullable = false, updatable = false) val submittedByDriverId: Long,
    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(nullable = false, columnDefinition = "jsonb", updatable = false) val original: ReportContent,
    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "before_photo_ids", nullable = false, columnDefinition = "jsonb", updatable = false) private val beforePhotos: List<Long>,
    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "after_photo_ids", nullable = false, columnDefinition = "jsonb", updatable = false) private val afterPhotos: List<Long>,
    @field:Column(nullable = false, updatable = false) val submittedAt: Instant,
) {
    init {
        validInput(beforePhotos.size in 3..5 && afterPhotos.size in 3..5)
        validInput((beforePhotos + afterPhotos).toSet().size == beforePhotos.size + afterPhotos.size)
    }

    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(nullable = false, updatable = false)
    var reportId: Long? = null
        protected set

    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(nullable = false, columnDefinition = "jsonb")
    var latest: ReportContent = original
        protected set

    @field:Column(nullable = false)
    var version: Int = 1
        protected set

    @field:Version
    @field:Column(nullable = false)
    var persistenceVersion: Long = 0
        protected set
    fun beforePhotoIds(): List<Long> = beforePhotos.toList()
    fun afterPhotoIds(): List<Long> = afterPhotos.toList()
    fun revise(expected: Int, content: ReportContent) {
        expectedVersion(version, expected)
        latest = content
        version++
    }
}

@Entity
@Immutable
@Table(name = "work_report_revisions")
class WorkReportRevision(
    @field:Column(nullable = false, updatable = false) val reportId: Long,
    @field:Column(nullable = false, updatable = false) val version: Int,
    @field:Column(nullable = false, updatable = false) val adminId: Long,
    @field:Column(nullable = false, columnDefinition = "text", updatable = false) val reason: String,
    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(nullable = false, columnDefinition = "jsonb", updatable = false) val beforeContent: ReportContent,
    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(nullable = false, columnDefinition = "jsonb", updatable = false) val afterContent: ReportContent,
    @field:Column(nullable = false, updatable = false) val revisedAt: Instant,
) {
    init {
        validInput(reason.isNotBlank())
    }

    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(nullable = false, updatable = false)
    var revisionId: Long? = null
        protected set
}
