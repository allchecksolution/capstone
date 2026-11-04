package com.allcheck

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTests {

    @Test
    fun verifyModuleStructure() {
        ApplicationModules.of(BackendApplication::class.java)
            .verify()
    }
}
