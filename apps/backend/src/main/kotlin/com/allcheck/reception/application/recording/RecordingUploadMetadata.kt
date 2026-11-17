package com.allcheck.reception.application.recording

/** 검증 전 업로드 메타데이터. 허용 형식·상한 검증은 설정된 업로드 정책을 거쳐야 한다. */
class RecordingUploadMetadata(
    val originalFilename: String,
    val contentType: String,
    val sizeBytes: Long,
    checksum: ByteArray,
) {
    private val digest = checksum.copyOf()
    val checksum: ByteArray get() = digest.copyOf()
}
