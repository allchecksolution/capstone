package com.allcheck

import com.allcheck.support.PostgresTestSchema
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import javax.sql.DataSource

@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource("classpath:jwt-test.properties")
class BackendApplicationTests {
    @Autowired
    private lateinit var dataSource: DataSource

    @AfterAll
    fun removeTestSchema() {
        database.remove(dataSource)
    }

    @Test
    fun contextLoads() {
    }

    companion object {
        private val database = PostgresTestSchema("application_context")

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            database.register(registry)
        }
    }
}
