package com.allcheck.identity

/** driver 등 다른 모듈이 인증 계정의 수명주기를 조정할 때 사용하는 공개 API. */
interface IdentityAccountOperations {
    /** 호출자의 쓰기 트랜잭션에서 활성 기사 계정을 잠근다. 이후 driver·세션 순서로 잠근다. */
    fun lockActiveDriverAccount(accountId: Long): AuthenticatedAccount

    /** 기사 인증 계정 비활성화와 전체 세션 폐기를 같은 트랜잭션에 반영한다. */
    fun deactivateDriverAccount(accountId: Long)
}
