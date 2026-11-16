package com.allcheck.support

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import java.util.UUID
import javax.sql.DataSource

/** 전체 컨텍스트 테스트도 애플리케이션 public 스키마를 건드리지 않고 자신이 만든 스키마만 사용한다. */
class PostgresTestSchema(prefix: String) {
    val name: String

    init {
        require(prefix.matches(Regex("[a-z_]{1,25}")))
        name = prefix + "_" + UUID.randomUUID().toString().replace("-", "")
    }

    fun register(registry: DynamicPropertyRegistry) {
        val url = System.getenv("TEST_DATABASE_URL") ?: "jdbc:postgresql://localhost:15432/allcheck"
        registry.add("spring.datasource.url") { url + (if ('?' in url) "&" else "?") + "currentSchema=$name" }
        registry.add("spring.datasource.username") { System.getenv("TEST_DATABASE_USERNAME") ?: "allcheck" }
        registry.add("spring.datasource.password") { System.getenv("TEST_DATABASE_PASSWORD") ?: "allcheck_local_6" }
        registry.add("spring.flyway.schemas") { name }
        registry.add("spring.flyway.default-schema") { name }
        registry.add("spring.jpa.properties.hibernate.default_schema") { name }
    }

    fun remove(dataSource: DataSource) {
        JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS $name CASCADE")
    }
}
