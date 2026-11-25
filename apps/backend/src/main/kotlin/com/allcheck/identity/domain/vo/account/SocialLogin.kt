package com.allcheck.identity.domain.vo.account

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated

@Embeddable
class SocialLogin private constructor(
    @Enumerated(EnumType.STRING)
    @Column(name = "social_provider", length = 16)
    val provider: SocialProvider,
    @Column(name = "social_user_id", length = 64)
    val providerUserId: String,
) {
    companion object {
        fun kakao(userId: Long): SocialLogin {
            require(userId > 0) { "Kakao user ID must be positive." }
            return SocialLogin(SocialProvider.KAKAO, userId.toString())
        }
    }
}
