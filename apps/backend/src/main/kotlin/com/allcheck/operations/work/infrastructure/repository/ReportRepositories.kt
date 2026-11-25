package com.allcheck.operations.work.infrastructure.repository

import com.allcheck.operations.work.domain.entity.WorkPhoto
import com.allcheck.operations.work.domain.entity.WorkReport
import com.allcheck.operations.work.domain.entity.WorkReportRevision
import org.springframework.data.jpa.repository.JpaRepository

interface WorkReportRepository : JpaRepository<WorkReport, Long> {
    fun findAllByWorkIdIn(workIds: Collection<Long>): List<WorkReport>
}
interface WorkReportRevisionRepository : JpaRepository<WorkReportRevision, Long> {
    fun findAllByReportIdOrderByVersion(reportId: Long): List<WorkReportRevision>
}
interface WorkPhotoRepository : JpaRepository<WorkPhoto, Long>
