package com.allcheck.reception.infrastructure.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration

/** 녹음용 서명 URL 유효기간. 실제 URL 발급은 S3 연동에서 이 설정을 사용한다. */
@Component
class RecordingUrlProperties(
    @Value("\${reception.recording.upload-url-ttl}") val uploadUrlTtl: Duration,
    @Value("\${reception.recording.playback-url-ttl}") val playbackUrlTtl: Duration,
    @Value("\${reception.recording.ai-download-url-ttl}") val aiDownloadUrlTtl: Duration,
) {
    init {
        listOf(uploadUrlTtl, playbackUrlTtl, aiDownloadUrlTtl).forEach { ttl ->
            require(ttl >= Duration.ofSeconds(1) && ttl <= Duration.ofDays(7) && ttl.nano == 0) {
                "Recording URL TTL must be a whole number of seconds between 1 second and 7 days."
            }
        }
    }
}
