package com.allcheck.identity

/** 서비스 내부 전달용 토큰이며 로그·HTTP 응답에 노출하지 않는다. */
class PushTarget(val accountId: Long, val installationId: String, val token: String, val tokenHash: String)

interface PushDeviceOperations {
    fun activeAdminAccountIds(): List<Long>
    fun activeDevices(accountIds: Collection<Long>): List<PushTarget>
    fun currentDevice(installationId: String, accountId: Long): PushTarget?
    fun invalidate(target: PushTarget)
}
