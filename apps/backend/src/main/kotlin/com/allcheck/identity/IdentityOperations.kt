package com.allcheck.identity

/** 다른 모듈과 보안 계층에 공개하는 인증 상태 조회 계약. */
interface IdentityOperations {
    /** 계정과 연결된 세션이 현재 사용 가능한 경우에만 인증 정보를 반환한다. */
    fun findAuthenticatedAccount(accountId: Long, sessionId: Long): AuthenticatedAccount?
}
