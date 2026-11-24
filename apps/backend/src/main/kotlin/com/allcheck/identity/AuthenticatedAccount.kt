package com.allcheck.identity

data class AuthenticatedAccount(
    val accountId: Long,
    val userType: UserType,
    val role: AdminRole?,
)
