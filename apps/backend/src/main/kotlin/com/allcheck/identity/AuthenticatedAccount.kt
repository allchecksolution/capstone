package com.allcheck.identity

data class AuthenticatedAccount(
    val accountId: String,
    val userType: UserType,
    val role: AdminRole?,
)
