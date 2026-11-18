package com.allcheck.identity.infrastructure.password

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AdminPasswordHasherTest {
    private val passwords = AdminPasswordHasher()

    @Test
    fun `같은 비밀번호도 서로 다른 salt로 저장하고 올바른 원문만 검증한다`() {
        // given
        val password = "관리자-password-!"

        // when
        val first = passwords.encode(password)
        val second = passwords.encode(password)

        // then
        assertThat(first).isNotEqualTo(second).doesNotContain(password).hasSizeLessThanOrEqualTo(255)
        assertThat(passwords.matches(password, first)).isTrue()
        assertThat(passwords.matches(password, second)).isTrue()
        assertThat(passwords.matches("wrong-password", first)).isFalse()
    }

    @Test
    fun `200자 유니코드 비밀번호를 자르거나 공백을 정규화하지 않는다`() {
        // given
        val password = "가".repeat(197) + " Z "
        val encoded = passwords.encode(password)

        // when & then
        assertThat(passwords.matches(password, encoded)).isTrue()
        assertThat(passwords.matches(password.take(199) + "X", encoded)).isFalse()
        assertThat(passwords.matches(password.trim(), encoded)).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["plain-text-password", "{noop}password", "{bcrypt}invalid", "{pbkdf2-sha256-600000}bad", "{pbkdf2-sha256-600000}zzzz"])
    fun `잘못되거나 지원하지 않는 저장 해시는 인증에 사용하지 않는다`(stored: String) {
        // when & then
        assertThat(passwords.matches("password", stored)).isFalse()
    }

    @Test
    fun `계정이 없어도 인증 결과는 실패다`() {
        // when & then
        assertThat(passwords.matches("password", null)).isFalse()
    }
}
