package com.allcheck.identity.domain

import com.allcheck.identity.domain.entity.Account
import com.allcheck.identity.domain.vo.AdminCredential
import com.allcheck.identity.domain.vo.AdminRole
import com.allcheck.identity.domain.vo.SocialLogin
import com.allcheck.identity.domain.vo.UserType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class AccountTest {
    private val createdAt = Instant.parse("2026-11-16T00:00:00Z")

    @Test
    fun `기사 인증 계정은 카카오 연결만 소유하고 가입 상태를 갖지 않는다`() {
        // given & when
        val account = Account.driver(SocialLogin.kakao(123456789), createdAt)

        // then
        assertThat(account.userType).isEqualTo(UserType.DRIVER)
        assertThat(account.socialLogin?.providerUserId).isEqualTo("123456789")
        assertThat(account.adminCredential).isNull()
        assertThat(account.enabled).isTrue()
    }

    @Test
    fun `관리자 인증 계정은 인코딩된 자격 증명과 역할을 소유한다`() {
        // given
        val credential = AdminCredential("admin01", "encoded-test-password", AdminRole.MASTER)

        // when
        val account = Account.administrator(credential, createdAt)

        // then
        assertThat(account.userType).isEqualTo(UserType.ADMIN)
        assertThat(account.adminCredential).isSameAs(credential)
        assertThat(account.socialLogin).isNull()
    }

    @Test
    fun `계정을 반복 비활성화해도 최초 비활성화 시각을 보존한다`() {
        // given
        val account = Account.driver(SocialLogin.kakao(123456789), createdAt)
        val disabledAt = createdAt.plusSeconds(60)

        // when
        account.disable(disabledAt)
        account.disable(disabledAt.plusSeconds(60))

        // then
        assertThat(account.enabled).isFalse()
        assertThat(account.disabledAt).isEqualTo(disabledAt)
    }

    @Test
    fun `계정 생성 전 시각으로 비활성화할 수 없다`() {
        // given
        val account = Account.driver(SocialLogin.kakao(123456789), createdAt)

        // when & then
        assertThatThrownBy { account.disable(createdAt.minusSeconds(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(account.enabled).isTrue()
    }
}
