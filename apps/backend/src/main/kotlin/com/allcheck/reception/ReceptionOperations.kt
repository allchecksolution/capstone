package com.allcheck.reception

import java.time.Instant
import java.time.OffsetDateTime

/** 의뢰 도메인이 사용할 접수의 공개 모듈 계약. 기사에게 직접 공개하는 API가 아니다. */
interface ReceptionOperations {
    fun confirmedSnapshot(adminId: Long, receptionId: Long, expectedVersion: Int): ReceptionSnapshot

    /** 호출하는 의뢰 생성 트랜잭션에 참여한다. 독립 호출은 허용하지 않는다. */
    fun registerRequest(adminId: Long, receptionId: Long, expectedVersion: Int, requestId: Long): ReceptionSnapshot
}

data class ReceptionSnapshot(
    val receptionId: Long,
    val receptionVersion: Int,
    val workType: String,
    val symptoms: String,
    val address: String,
    val desiredVisitFrom: OffsetDateTime,
    val desiredVisitTo: OffsetDateTime,
    val contactPhone: String,
    val notes: String?,
    val confirmedAt: Instant,
)
