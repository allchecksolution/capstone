package com.allcheck.identity.presentation.cli

import com.allcheck.global.exception.BusinessException
import com.allcheck.identity.AdminRole
import com.allcheck.identity.application.ProvisionedAdministrator
import com.allcheck.identity.exception.IdentityErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.BeanCreationException
import org.springframework.dao.DataAccessResourceFailureException
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class AdminProvisioningCliTest {
    @TempDir lateinit var directory: Path

    private val output = ByteArrayOutputStream()
    private val error = ByteArrayOutputStream()
    private var calls = 0
    private var capturedPassword: String? = null
    private var action: (String, String, AdminRole) -> ProvisionedAdministrator = { id, password, role ->
        calls++
        capturedPassword = password
        ProvisionedAdministrator("created-account", id, role)
    }

    private fun cli(console: () -> CharArray? = { null }): AdminProvisioningCli = AdminProvisioningCli(
        provision = { id, password, role -> action(id, password, role) },
        readConsolePassword = console,
        output = PrintStream(output, true, StandardCharsets.UTF_8),
        error = PrintStream(error, true, StandardCharsets.UTF_8),
    )

    @Test
    fun `도움말은 비밀번호 입력이나 계정 발급을 실행하지 않는다`() {
        // when
        val result = cli { error("Help must not read a password") }.run(arrayOf("--help"))

        // then
        assertThat(result).isZero()
        assertThat(calls).isZero()
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("provision-admin", "--password-file")
        assertThat(error.size()).isZero()
    }

    @Test
    fun `콘솔 비밀번호는 결과에 노출하지 않고 입력 배열을 지운다`() {
        // given
        val password = "console-secret-value".toCharArray()

        // when
        val result = cli { password }.run(arrayOf("--login-id=operator", "--role=MASTER"))

        // then
        assertThat(result).isZero()
        assertThat(calls).isEqualTo(1)
        assertThat(capturedPassword).isEqualTo("console-secret-value")
        assertThat(password).containsOnly('\u0000')
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("ADMIN_CREATED", "role=MASTER").doesNotContain("console-secret-value")
        assertThat(error.size()).isZero()
    }

    @Test
    fun `파일의 200자 유니코드 비밀번호와 끝 공백을 원문 그대로 전달한다`() {
        // given
        val password = "가".repeat(197) + " Z "
        val file = directory.resolve("password with spaces.txt")
        Files.writeString(file, password)

        // when
        val result = cli().run(arrayOf("--login-id=operator", "--role=SUB", "--password-file=$file"))

        // then
        assertThat(result).isZero()
        assertThat(capturedPassword).isEqualTo(password)
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("role=SUB").doesNotContain(password)
    }

    @ParameterizedTest
    @ValueSource(strings = ["--unknown=value", "--password=do-not-echo-this-secret", "--role=MASTER", "--login-id=another", "--help", "positional-value"])
    fun `알 수 없는 인자 중복 인자와 명령줄 비밀번호는 발급 전에 거부한다`(extra: String) {
        // when
        val result = cli().run(arrayOf("--login-id=operator", "--role=SUB", extra))

        // then
        assertThat(result).isEqualTo(2)
        assertThat(calls).isZero()
        assertThat(error.toString(StandardCharsets.UTF_8)).contains("ADMIN_PROVISIONING_INPUT_INVALID").doesNotContain("do-not-echo-this-secret")
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing-id", "missing-role", "unknown-role", "blank-id", "long-id", "no-console"])
    fun `필수 입력과 명시적 관리자 역할을 검증한다`(scenario: String) {
        // given
        val args = when (scenario) {
            "missing-id" -> arrayOf("--role=MASTER")
            "missing-role" -> arrayOf("--login-id=operator")
            "unknown-role" -> arrayOf("--login-id=operator", "--role=SUPER")
            "blank-id" -> arrayOf("--login-id= ", "--role=MASTER")
            "long-id" -> arrayOf("--login-id=${"x".repeat(101)}", "--role=MASTER")
            else -> arrayOf("--login-id=operator", "--role=MASTER")
        }

        // when & then
        assertThat(cli().run(args)).isEqualTo(2)
        assertThat(calls).isZero()
    }

    @ParameterizedTest
    @ValueSource(strings = ["empty", "blank", "long", "oversized-file", "invalid-utf8", "missing-file"])
    fun `잘못된 비밀번호 파일은 DB 처리 전에 거부한다`(scenario: String) {
        // given
        val file = directory.resolve("password.txt")
        when (scenario) {
            "empty" -> Files.writeString(file, "")
            "blank" -> Files.writeString(file, " \n ")
            "long" -> Files.writeString(file, "x".repeat(201))
            "oversized-file" -> Files.writeString(file, "x".repeat(801))
            "invalid-utf8" -> Files.write(file, byteArrayOf(0xc3.toByte(), 0x28))
            else -> Unit
        }

        // when & then
        assertThat(cli().run(arrayOf("--login-id=operator", "--role=MASTER", "--password-file=$file"))).isEqualTo(2)
        assertThat(calls).isZero()
    }

    @Test
    fun `중복 아이디 오류는 코드만 출력하고 비밀번호는 출력하지 않는다`() {
        // given
        action = { _, _, _ -> throw BusinessException(IdentityErrorCode.ADMIN_LOGIN_ID_ALREADY_EXISTS) }

        // when
        val result = cli { "secret-password".toCharArray() }.run(arrayOf("--login-id=operator", "--role=MASTER"))

        // then
        assertThat(result).isEqualTo(1)
        assertThat(error.toString(StandardCharsets.UTF_8).trim()).isEqualTo("ADMIN_LOGIN_ID_ALREADY_EXISTS")
        assertThat(output.size()).isZero()
    }

    @ParameterizedTest
    @ValueSource(strings = ["database", "configuration"])
    fun `기술 오류의 원문과 자격 증명을 출력하지 않는다`(failure: String) {
        // given
        action = { _, _, _ ->
            if (failure == "database") throw DataAccessResourceFailureException("private-secret")
            throw BeanCreationException("bean", "private-secret")
        }

        // when
        val result = cli { "secret-password".toCharArray() }.run(arrayOf("--login-id=operator", "--role=MASTER"))

        // then
        assertThat(result).isEqualTo(1)
        assertThat(error.toString(StandardCharsets.UTF_8)).contains("ADMIN_PROVISIONING_").doesNotContain("private-secret", "secret-password")
    }
}
