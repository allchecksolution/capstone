package com.allcheck.reception.domain.vo.recording

import com.allcheck.global.exception.BusinessException
import com.allcheck.reception.exception.ReceptionErrorCode

/** WAV 컨테이너만 허용한다. 코덱/샘플레이트와 녹음 길이는 여기서 임의로 제한하지 않는다. */
object WavRecording {
    private val MIME_TYPES = setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
    private val FILENAME = Regex("^rec_(.+)_([+0-9-]{9,20})_([+0-9-]{9,20})@\\d{6}_\\d{6}\\.wav$", RegexOption.IGNORE_CASE)
    private val PHONE = Regex("\\+?[0-9]{9,15}")

    fun validateMetadata(filename: String, contentType: String) {
        if (!filename.endsWith(".wav", ignoreCase = true) || !supportsContentType(contentType)) {
            throw BusinessException(ReceptionErrorCode.RECORDING_FORMAT_NOT_SUPPORTED)
        }
    }

    /** 두 번호의 역할은 확인되지 않았다. 유효한 두 값이 동일할 때만 자동 채움하고 그 외에는 수동 확인한다. */
    fun contactPhone(filename: String): String? {
        val match = FILENAME.matchEntire(filename) ?: return null
        val phones = listOf(match.groupValues[2], match.groupValues[3]).map { it.replace("-", "") }
        if (phones.any { !PHONE.matches(it) } || phones.distinct().size != 1) return null
        return phones.first()
    }

    fun supportsContentType(contentType: String) = contentType.lowercase() in MIME_TYPES

    fun hasWaveHeader(bytes: ByteArray): Boolean {
        if (bytes.size != 12) return false
        val container = bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)
        return container in setOf("RIFF", "RIFX", "RF64", "BW64") && bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray(Charsets.US_ASCII))
    }
}
