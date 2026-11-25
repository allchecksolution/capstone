package com.allcheck.global.ratelimit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** 기술용 카운터. 상태 변경은 저장소의 원자적 SQL로만 수행한다. */
@Entity
@Table(name = "request_limit_buckets")
class RequestLimitBucket(
    @Id
    @Column(name = "bucket_id", nullable = false, length = 128)
    val id: String,
    @Column(name = "request_count", nullable = false)
    val requestCount: Int,
    @Column(name = "window_ends_at", nullable = false)
    val windowEndsAt: Instant,
)
