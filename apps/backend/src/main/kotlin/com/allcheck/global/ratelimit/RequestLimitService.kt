package com.allcheck.global.ratelimit

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class RequestLimitService(private val buckets: RequestLimitBucketRepository) {
    /** 실패한 업무 요청도 집계한다. 호출자는 외부 호출이나 업무 쓰기 트랜잭션 전에 실행한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = [RequestLimitExceededException::class])
    fun check(subjectId: String, policy: RequestLimitPolicy) {
        require(subjectId.isNotBlank() && subjectId.length <= 64)
        val result = buckets.consume("${policy.scope}:$subjectId", policy.maxRequests, policy.window.seconds)
        if (!result.allowed) throw RequestLimitExceededException(result.retryAfterSeconds)
    }
}
