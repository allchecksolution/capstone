package com.allcheck.operations.work

data class WorkReference(val workId: Long, val requestId: Long, val driverId: Long)

/** 배차 모듈이 실제 작업 배정을 기록할 때 사용하는 공개 계약. */
interface WorkOperations {
    fun recordAssignment(requestId: Long, driverId: Long): WorkReference
}
