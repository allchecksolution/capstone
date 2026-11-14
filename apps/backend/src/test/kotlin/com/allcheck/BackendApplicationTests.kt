package com.allcheck

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource("classpath:jwt-test.properties")
class BackendApplicationTests {

    @Test
    fun contextLoads() {
    }
}
