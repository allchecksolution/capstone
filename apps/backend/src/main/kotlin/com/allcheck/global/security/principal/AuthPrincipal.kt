package com.allcheck.global.security.principal

import com.allcheck.identity.AdminRole
import com.allcheck.identity.UserType
import java.security.Principal

data class AuthPrincipal(
    val accountId: String,
    val sessionId: String,
    val userType: UserType,
    val role: AdminRole?,
) : Principal {
    override fun getName(): String = accountId
}
