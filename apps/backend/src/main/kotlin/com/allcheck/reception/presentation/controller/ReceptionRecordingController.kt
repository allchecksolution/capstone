package com.allcheck.reception.presentation.controller

import com.allcheck.reception.application.recording.ReceptionRecordingService
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.presentation.api.ReceptionRecordingApi
import com.allcheck.reception.presentation.dto.request.recording.CompleteRecordingUploadRequest
import com.allcheck.reception.presentation.dto.request.recording.RecordingUploadRequest
import com.allcheck.reception.presentation.dto.response.recording.RecordingUploadCompletedResponse
import com.allcheck.reception.presentation.dto.response.recording.RecordingUploadGrantResponse
import com.allcheck.reception.presentation.security.ReceptionAdminResolver
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.RestController

@RestController
@PreAuthorize("hasRole('ADMIN')")
class ReceptionRecordingController(private val service: ReceptionRecordingService, private val admins: ReceptionAdminResolver) : ReceptionRecordingApi {
    override fun upload(authentication: Authentication, receptionId: String, key: String, request: RecordingUploadRequest): ResponseEntity<RecordingUploadGrantResponse> {
        val result = service.upload(admins.resolve(authentication), ReceptionExternalId.RECEPTION.parse(receptionId), key, request.expectedVersion, request.toMetadata())
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).header("Idempotency-Replayed", result.replayed.toString()).body(result.value)
    }

    override fun renewUpload(authentication: Authentication, receptionId: String, recordingFileId: String, key: String): ResponseEntity<RecordingUploadGrantResponse> {
        val result = service.renewUpload(admins.resolve(authentication), ReceptionExternalId.RECEPTION.parse(receptionId), ReceptionExternalId.RECORDING.parse(recordingFileId), key)
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).header("Idempotency-Replayed", result.replayed.toString()).body(result.value)
    }

    override fun access(authentication: Authentication, receptionId: String, recordingFileId: String) = ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(service.access(admins.resolve(authentication), ReceptionExternalId.RECEPTION.parse(receptionId), ReceptionExternalId.RECORDING.parse(recordingFileId)))

    override fun complete(authentication: Authentication, receptionId: String, recordingFileId: String, key: String, request: CompleteRecordingUploadRequest): ResponseEntity<RecordingUploadCompletedResponse> {
        val result = service.complete(admins.resolve(authentication), ReceptionExternalId.RECEPTION.parse(receptionId), ReceptionExternalId.RECORDING.parse(recordingFileId), key, request.expectedVersion)
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).header("Location", result.value.analysis.statusUrl)
            .header("Idempotency-Replayed", result.replayed.toString()).body(result.value)
    }
}
