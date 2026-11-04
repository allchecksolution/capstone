package com.allcheck

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

class DocumentationTests {

    @Test
    fun generateDocumentation() {
        val modules = ApplicationModules.of(BackendApplication::class.java)

        Documenter(modules)
            .writeModulesAsPlantUml()
            .writeIndividualModulesAsPlantUml()
            .writeModuleCanvases()
            .writeAggregatingDocument()
    }
}
