package com.allcheck.identity.domain.vo

import com.allcheck.identity.AdminRole
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated

@Embeddable
class AdminCredential(
    @Column(name = "login_id", length = 100)
    val loginId: String,
    @Column(name = "password_hash", length = 255)
    val passwordHash: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "admin_role", length = 16)
    val role: AdminRole,
) {
    init {
        require(loginId.isNotBlank() && loginId.length <= 100) { "Invalid administrator login ID." }
        require(passwordHash.isNotBlank() && passwordHash.length <= 255) { "Invalid encoded password." }
    }
}
