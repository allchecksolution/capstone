package com.allcheck.operations.work.domain.entity

import com.allcheck.operations.PhotoPhase
import com.allcheck.operations.validInput
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.Base64

@Entity
@Table(name = "work_photos")
class WorkPhoto(
    @field:Column(nullable = false, updatable = false) val workId: Long,
    @field:Column(nullable = false, updatable = false) val uploadedByDriverId: Long,
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 10, updatable = false) val phase: PhotoPhase,
    @field:Column(nullable = false, length = 255, updatable = false) val originalFilename: String,
    @field:Column(nullable = false, length = 63, updatable = false) val bucket: String,
    @field:Column(nullable = false, columnDefinition = "text", updatable = false) val objectKey: String,
    @field:Column(nullable = false, length = 100, updatable = false) val contentType: String,
    @field:Column(nullable = false, updatable = false) val sizeBytes: Long,
    @field:Column(nullable = false, columnDefinition = "bytea", updatable = false) val checksumSha256: ByteArray,
    @field:Column(nullable = false, updatable = false) val createdAt: Instant,
) {
    init {
        validInput(contentType in setOf("image/jpeg", "image/png") && sizeBytes in 1..10_000_000)
        validInput(checksumSha256.size == 32 && originalFilename.isNotBlank() && originalFilename.length <= 255)
    }

    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(nullable = false, updatable = false)
    var photoFileId: Long? = null
        protected set

    @field:Column(nullable = false, length = 20)
    var status: String = "PENDING"
        protected set

    @field:Column
    var uploadedAt: Instant? = null
        protected set

    @field:Column(nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    @field:Version
    @field:Column(nullable = false)
    var persistenceVersion: Long = 0
        protected set
    fun complete(now: Instant) {
        if (status == "PENDING") {
            status = "UPLOADED"
            uploadedAt = now
            updatedAt = now
        }
    }
    fun checksumBase64(): String = Base64.getEncoder().encodeToString(checksumSha256)
}
