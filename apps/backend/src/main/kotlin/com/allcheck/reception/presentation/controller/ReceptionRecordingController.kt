package com.allcheck.reception.presentation.controller

import com.allcheck.global.security.principal.AuthPrincipal
import com.allcheck.reception.application.recording.ReceptionRecordingService
import com.allcheck.reception.domain.vo.ReceptionExternalId
import com.allcheck.reception.presentation.api.ReceptionRecordingApi
import com.allcheck.reception.presentation.dto.request.recording.CompleteRecordingUploadRequest
import com.allcheck.reception.presentation.dto.request.recording.RecordingUploadRequest
import com.allcheck.reception.presentation.dto.response.recording.RecordingUploadCompletedResponse
import com.allcheck.reception.presentation.dto.response.recording.RecordingUploadGrantResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RestController

@RestController
@PreAuthorize("hasRole('ADMIN')")
class ReceptionRecordingController(private val service: ReceptionRecordingService) : ReceptionRecordingApi {
    override fun upload(principal: AuthPrincipal?, receptionId: String, key: String, request: RecordingUploadRequest): ResponseEntity<RecordingUploadGrantResponse> {
        val result = service.upload(principal.requireReceptionAdminId(), ReceptionExternalId.RECEPTION.parse(receptionId), key, request.expectedVersion, request.toMetadata())
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).header("Idempotency-Replayed", result.replayed.toString()).body(result.value)
    }

    override fun renewUpload(principal: AuthPrincipal?, receptionId: String, recordingFileId: String, key: String): ResponseEntity<RecordingUploadGrantResponse> {
        val result = service.renewUpload(principal.requireReceptionAdminId(), ReceptionExternalId.RECEPTION.parse(receptionId), ReceptionExternalId.RECORDING.parse(recordingFileId), key)
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).header("Idempotency-Replayed", result.replayed.toString()).body(result.value)
    }

    override fun access(principal: AuthPrincipal?, receptionId: String, recordingFileId: String) = ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(service.access(principal.requireReceptionAdminId(), ReceptionExternalId.RECEPTION.parse(receptionId), ReceptionExternalId.RECORDING.parse(recordingFileId)))

    override fun complete(principal: AuthPrincipal?, receptionId: String, recordingFileId: String, key: String, request: CompleteRecordingUploadRequest): ResponseEntity<RecordingUploadCompletedResponse> {
        val result = service.complete(principal.requireReceptionAdminId(), ReceptionExternalId.RECEPTION.parse(receptionId), ReceptionExternalId.RECORDING.parse(recordingFileId), key, request.expectedVersion)
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).header("Location", result.value.analysis.statusUrl)
            .header("Idempotency-Replayed", result.replayed.toString()).body(result.value)
    }
}
