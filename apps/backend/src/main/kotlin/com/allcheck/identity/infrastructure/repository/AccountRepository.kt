package com.allcheck.identity.infrastructure.repository

import com.allcheck.identity.domain.entity.Account
import com.allcheck.identity.domain.vo.SocialLogin
import org.springframework.data.jpa.repository.JpaRepository

interface AccountRepository : JpaRepository<Account, String> {
    fun findBySocialLogin(socialLogin: SocialLogin): Account?

    fun findByAdminCredentialLoginId(loginId: String): Account?
}
