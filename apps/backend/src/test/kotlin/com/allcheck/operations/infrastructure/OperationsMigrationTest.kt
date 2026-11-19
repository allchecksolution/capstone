package com.allcheck.operations.infrastructure

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

class OperationsMigrationTest {
    @Test
    fun `출처가 없는 기존 사진은 새 작업 ID에 연결하지 않고 마이그레이션을 안전하게 중단한다`() {
        // given: 테스트가 생성한 스키마만 마이그레이션하고 제거한다.
        val schema = "operations_upgrade_" + UUID.randomUUID().toString().replace("-", "")
        val base = System.getenv("TEST_DATABASE_URL") ?: "jdbc:postgresql://localhost:15432/allcheck"
        val source = DriverManagerDataSource(base + (if ('?' in base) "&" else "?") + "currentSchema=$schema", System.getenv("TEST_DATABASE_USERNAME") ?: "allcheck", System.getenv("TEST_DATABASE_PASSWORD") ?: "allcheck_local_6")
        val jdbc = JdbcTemplate(source)
        fun flyway(target: String) = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target(target).load()
        try {
            flyway("11").migrate()
            jdbc.update(
                """
                insert into work_photos(work_id,uploaded_by_driver_id,phase,original_filename,bucket,object_key,content_type,size_bytes,checksum_sha256,status)
                values(101,10,'BEFORE','test.jpg','test-photos','legacy/photo','image/jpeg',100,?,'PENDING')
                """.trimIndent(),
                ByteArray(32) { 1 },
            )
            // when
            assertThrows<FlywayException> { flyway("latest").migrate() }
            // then
            assertThat(jdbc.queryForObject("select count(*) from work_photos where work_id=101", Long::class.java)).isEqualTo(1)
            assertThat(jdbc.queryForObject("select to_regclass('$schema.service_requests')::text", String::class.java)).isNull()
            assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history where success and version is not null", Int::class.java)).isEqualTo(11)
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }
}
