package com.allcheck.identity.domain.entity

import com.allcheck.identity.domain.vo.AdminCredential
import com.allcheck.identity.domain.vo.SocialLogin
import com.allcheck.identity.domain.vo.UserType
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "identity_accounts",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_identity_accounts_social_login", columnNames = ["social_provider", "social_user_id"]),
        UniqueConstraint(name = "uq_identity_accounts_login_id", columnNames = ["login_id"]),
    ],
)
class Account private constructor(
    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false, length = 16, updatable = false)
    val userType: UserType,
    @Embedded
    val socialLogin: SocialLogin?,
    @Embedded
    val adminCredential: AdminCredential?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Id
    @Column(name = "account_id", nullable = false, length = 64, updatable = false)
    val id: String = UUID.randomUUID().toString()

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true
        protected set

    @Column(name = "disabled_at")
    var disabledAt: Instant? = null
        protected set

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    fun disable(now: Instant) {
        require(!now.isBefore(createdAt)) { "Account deactivation cannot precede creation." }
        if (!enabled) return
        enabled = false
        disabledAt = now
    }

    companion object {
        fun driver(socialLogin: SocialLogin, createdAt: Instant): Account = Account(UserType.DRIVER, socialLogin, null, createdAt)

        fun administrator(credential: AdminCredential, createdAt: Instant): Account = Account(UserType.ADMIN, null, credential, createdAt)
    }
}
