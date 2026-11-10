package com.allcheck.global.security.principal

// TODO: 기사·관리자 데이터 모델 확정 후 식별자·사용자 유형의 타입 및 JWT Claims 매핑을 정한다.
data class AuthPrincipal(
    val userId: String,
    val userType: String,
)

// TODO: Authentication 생성, Principal 등록 및 GrantedAuthority 연결은 인증 방식 확정 후 구현한다.
// Resource Server의 기본 Principal은 Jwt이며, AuthPrincipal 사용 시 Converter 연결이 필요하다.
