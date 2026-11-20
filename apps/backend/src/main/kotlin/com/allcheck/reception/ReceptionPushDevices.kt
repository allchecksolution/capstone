package com.allcheck.reception

/** Identity가 현재 활성 관리자/기기 소유권을 확인해 제공하는 공개 연동 경계. 접수는 기기 등록 데이터를 소유하지 않는다. */
interface ReceptionPushDevices {
    fun activeInstallations(adminId: Long): List<String>

    /** 재시도마다 현재 연결과 토큰을 다시 확인한다. 해제/이전/접근 불가이면 null이다. */
    fun currentBinding(adminId: Long, installationId: String): ReceptionPushBinding?

    /** 아직 해당 토큰인 경우에만 해제한다. 이미 갱신된 토큰을 무효화하지 않는다. */
    fun invalidateBinding(adminId: Long, installationId: String, rejected: ReceptionPushBinding)
}

data class ReceptionPushBinding(val token: String, val version: Long) {
    override fun toString(): String = "ReceptionPushBinding(version=$version)"
}
