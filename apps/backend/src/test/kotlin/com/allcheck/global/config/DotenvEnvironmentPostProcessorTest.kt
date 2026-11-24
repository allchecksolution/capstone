package com.allcheck.global.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.Banner
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.nio.file.Files
import java.nio.file.Path

class DotenvEnvironmentPostProcessorTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `실제 env 파일 대신 테스트 파일에서 JWT 카카오 국세청 설정과 따옴표를 읽는다`() {
        // given
        val environment = environment(
            """
            # synthetic fixtures only
            JWT_SECRET="synthetic-base64=="
            export KAKAO_APP_ID=123456
            NTS_SERVICE_KEY='key==#literal'
            SAMPLE=" spaced value " # comment
            EMPTY=
            """.trimIndent(),
        )

        // when
        load(environment)

        // then
        assertThat(environment.getProperty("JWT_SECRET")).isEqualTo("synthetic-base64==")
        assertThat(environment.getProperty("KAKAO_APP_ID")).isEqualTo("123456")
        assertThat(environment.getProperty("NTS_SERVICE_KEY")).isEqualTo("key==#literal")
        assertThat(environment.getProperty("SAMPLE")).isEqualTo(" spaced value ")
        assertThat(environment.getProperty("EMPTY")).isEmpty()
    }

    @Test
    fun `기존 환경변수와 JVM 설정을 덮어쓰지 않으며 파일 설정은 기본값보다 우선한다`() {
        // given
        val environment = environment("JWT_SECRET=file-secret\nKAKAO_APP_ID=123\nSPRING_DATASOURCE_URL=jdbc:fixture")
        environment.propertySources.replace("systemEnvironment", SystemEnvironmentPropertySource("systemEnvironment", mapOf("JWT_SECRET" to "existing-secret")))
        environment.propertySources.addFirst(MapPropertySource("jvm-test", mapOf("KAKAO_APP_ID" to "456")))
        environment.propertySources.addLast(MapPropertySource("defaults", mapOf("spring.datasource.url" to "jdbc:default")))

        // when
        load(environment)

        // then
        assertThat(environment.getProperty("JWT_SECRET")).isEqualTo("existing-secret")
        assertThat(environment.getProperty("KAKAO_APP_ID")).isEqualTo("456")
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo("jdbc:fixture")
    }

    @Test
    fun `env 파일이 없으면 환경변수만으로 실행할 수 있다`() {
        // given
        val environment = environment(null)

        // when
        load(environment)

        // then
        assertThat(environment.propertySources.contains("allcheckDotenv")).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["JWT_SECRET=\"private-unclosed", "JWT_SECRET=private\nJWT_SECRET=second-secret", "invalid private data", "KAKAO_APP_ID='123' private"])
    fun `잘못된 파일은 값이나 원문을 노출하지 않고 중단한다`(content: String) {
        // given
        val environment = environment(content)

        // when & then
        assertThatThrownBy { load(environment) }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Invalid .env format at line")
            .hasMessageNotContaining("private").hasMessageNotContaining("JWT_SECRET").hasMessageNotContaining("second-secret")
    }

    @Test
    fun `일반 서버와 CLI의 Spring 부트스트랩에서 자동 등록되고 실행 인자가 우선한다`() {
        // given
        val file = directory.resolve(".env")
        Files.writeString(file, "JWT_SECRET=fixture-only\nKAKAO_APP_ID=123456\nSPRING_PROFILES_ACTIVE=dotenv-fixture")
        val environment = environment(null)
        environment.propertySources.addFirst(MapPropertySource("fixture-path", mapOf("allcheck.env-file" to file.toString())))
        val application = SpringApplication(ProbeConfiguration::class.java)
        application.setEnvironment(environment)
        application.setWebApplicationType(WebApplicationType.NONE)
        application.setBannerMode(Banner.Mode.OFF)
        application.setLogStartupInfo(false)

        // when
        application.run("--KAKAO_APP_ID=654321").use { context ->
            // then: these are the actual placeholders used by application.yml, without a DB or external calls.
            assertThat(context.environment.getProperty("jwt.secret")).isEqualTo("fixture-only")
            assertThat(context.environment.getProperty("identity.kakao.app-id")).isEqualTo("654321")
            assertThat(context.environment.activeProfiles).contains("dotenv-fixture")
        }
    }

    private fun environment(content: String?): StandardEnvironment {
        val file = directory.resolve("fixture.env")
        if (content != null) Files.writeString(file, content)
        return StandardEnvironment().apply {
            propertySources.replace("systemEnvironment", SystemEnvironmentPropertySource("systemEnvironment", emptyMap()))
            propertySources.replace("systemProperties", MapPropertySource("systemProperties", emptyMap()))
            propertySources.addFirst(MapPropertySource("fixture-path", mapOf("allcheck.env-file" to file.toString())))
        }
    }

    private fun load(environment: StandardEnvironment) = DotenvEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

    @TestConfiguration(proxyBeanMethods = false)
    class ProbeConfiguration
}
