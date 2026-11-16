package com.allcheck.reception

import org.springframework.security.core.Authentication

/**
 * Identity 모듈이 제공할 관리자 식별 연동 계약.
 * 검증된 Authentication을 현재 접근 가능한 관리자 DB ID로 해석한다. 클라이언트 본문·임의 JWT claim을 신뢰하지 않는다.
 * 이 구현이 없으면 접수 HTTP API는 503으로 닫혀 있으며, 임의 사용자명→관리자 ID 변환을 하지 않는다.
 */
fun interface ReceptionAdministratorIdentity {
    fun resolveAdminId(authentication: Authentication): Long?
}
