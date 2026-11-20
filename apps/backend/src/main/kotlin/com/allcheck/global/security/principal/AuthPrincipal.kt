package com.allcheck.global.security.principal

import com.allcheck.identity.AdminRole
import com.allcheck.identity.UserType
import java.security.Principal

data class AuthPrincipal(
    val accountId: Long,
    val sessionId: Long,
    val userType: UserType,
    val role: AdminRole?,
) : Principal {
    override fun getName(): String = accountId.toString()
}
