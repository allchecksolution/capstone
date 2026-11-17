package com.allcheck

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTests {

    @Test
    fun verifyModuleStructure() {
        ApplicationModules.of(BackendApplication::class.java)
            .verify()
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
    fun `공통 보안 계층은 identity 내부 구현에 의존하지 않는다`() {
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
            ).check(classes)
    }
}
