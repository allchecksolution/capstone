package com.allcheck

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTests {

    @Test
    fun `애플리케이션과 응답 DTO는 요청 DTO에 의존하지 않는다`() {
        // given
        val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.allcheck")

        // when & then
        noClasses().that().resideInAnyPackage(
            "com.allcheck..application..",
            "com.allcheck..presentation.dto.response..",
        ).should().dependOnClassesThat().resideInAPackage(
            "com.allcheck..presentation.dto.request..",
        ).check(classes)
    }

    @Test
    fun verifyModuleStructure() {
        ApplicationModules.of(BackendApplication::class.java)
            .verify()
    }

    @Test
    fun `채팅 도메인과 인프라는 애플리케이션 계층에 의존하지 않는다`() {
        val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.allcheck.chat")

        noClasses().that().resideInAnyPackage("com.allcheck.chat.domain..", "com.allcheck.chat.infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("com.allcheck.chat.application..")
            .check(classes)
    }

    @Test
    fun `공통 기술 코드는 비즈니스 모듈 내부에 의존하지 않는다`() {
        // given
        val classes = ClassFileImporter().importPackages("com.allcheck.global")

        // when & then
        noClasses().that().resideInAPackage("com.allcheck.global..")
            .should().dependOnClassesThat().resideInAnyPackage("com.allcheck.reception..")
            .check(classes)
    }

    @Test
    fun `공통 보안 계층은 업무 모듈 내부 구현에 의존하지 않는다`() {
        // given
        val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.allcheck")

        // when & then
        noClasses().that().resideInAPackage("com.allcheck.global..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.allcheck.identity.application..",
                "com.allcheck.identity.domain..",
                "com.allcheck.identity.infrastructure..",
                "com.allcheck.identity.presentation..",
                "com.allcheck.identity.exception..",
                "com.allcheck.driver.application..",
                "com.allcheck.driver.domain..",
                "com.allcheck.driver.infrastructure..",
                "com.allcheck.driver.presentation..",
                "com.allcheck.driver.exception..",
                "com.allcheck.operations.work.application..",
                "com.allcheck.operations.work.domain..",
                "com.allcheck.operations.work.infrastructure..",
            ).check(classes)
    }
}
