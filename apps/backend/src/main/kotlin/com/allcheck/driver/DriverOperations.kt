package com.allcheck.driver

data class DriverIdentity(val driverId: String, val accountId: String)

interface DriverOperations {
    fun isActiveDriverAccount(accountId: String): Boolean

    /** 호출자 쓰기 트랜잭션에서 인증 계정 → 활성 기사 순서로 잠근다. */
    fun lockActiveDriver(driverId: String): DriverIdentity
}

/** 작업 소유 모듈이 제공하는 조회 계약. driver가 작업 내부 모델에 의존하지 않게 한다. */
interface DriverWorkload {
    fun hasUnfinishedWork(driverId: String): Boolean
}
