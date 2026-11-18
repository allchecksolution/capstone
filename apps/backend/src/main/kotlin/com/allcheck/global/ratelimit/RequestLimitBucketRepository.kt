package com.allcheck.global.ratelimit

import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param

interface RequestLimitBucketRepository : Repository<RequestLimitBucket, String> {
    @Query(
        value = """
            INSERT INTO request_limit_buckets AS bucket (bucket_id, request_count, window_ends_at)
            VALUES (:bucketId, 1, statement_timestamp() + :windowSeconds * INTERVAL '1 second')
            ON CONFLICT (bucket_id) DO UPDATE SET
                request_count = CASE
                    WHEN bucket.window_ends_at <= statement_timestamp() THEN 1
                    ELSE LEAST(bucket.request_count, :maxRequests) + 1
                END,
                window_ends_at = CASE
                    WHEN bucket.window_ends_at <= statement_timestamp() THEN EXCLUDED.window_ends_at
                    ELSE bucket.window_ends_at
                END
            RETURNING request_count <= :maxRequests AS allowed,
                GREATEST(1, CEIL(EXTRACT(EPOCH FROM (window_ends_at - statement_timestamp())))) AS "retryAfterSeconds"
        """,
        nativeQuery = true,
    )
    fun consume(
        @Param("bucketId") bucketId: String,
        @Param("maxRequests") maxRequests: Int,
        @Param("windowSeconds") windowSeconds: Long,
    ): RequestLimitConsumption
}

interface RequestLimitConsumption {
    val allowed: Boolean
    val retryAfterSeconds: Long
}
