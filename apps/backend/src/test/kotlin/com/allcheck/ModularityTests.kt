package com.allcheck

import com.tngtech.archunit.core.importer.ClassFileImporter
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
}
