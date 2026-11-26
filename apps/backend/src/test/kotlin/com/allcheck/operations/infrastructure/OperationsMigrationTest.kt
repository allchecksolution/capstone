package com.allcheck.operations.infrastructure

import com.allcheck.support.insertDriverFixture
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
        withMainSchema { jdbc, schema, flyway ->
            jdbc.update(
                """
                insert into work_photos(work_id,uploaded_by_driver_id,phase,original_filename,bucket,object_key,content_type,size_bytes,checksum_sha256,status)
                values(101,10,'BEFORE','test.jpg','test-photos','legacy/photo','image/jpeg',100,?,'PENDING')
                """.trimIndent(),
                ByteArray(32) { 1 },
            )

            assertThrows<FlywayException> { flyway("latest").migrate() }

            assertThat(jdbc.queryForObject("select count(*) from work_photos where work_id=101", Long::class.java)).isEqualTo(1)
            assertThat(jdbc.queryForObject("select to_regclass('$schema.service_requests')::text", String::class.java)).isNull()
            assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history where success and version is not null", Int::class.java)).isEqualTo(17)
        }
    }

    @Test
    fun `V17에서 기존 작업 테이블과 제약을 유지한 상태로 배차 스키마를 추가한다`() {
        withMainSchema { jdbc, _, flyway ->
            val before = jdbc.queryForObject("select 'work_orders'::regclass::oid::bigint", Long::class.java)

            flyway("latest").migrate()
            flyway("latest").validate()

            assertThat(jdbc.queryForObject("select 'work_orders'::regclass::oid::bigint", Long::class.java)).isEqualTo(before)
            val columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_schema=current_schema() and table_name='work_orders'",
                String::class.java,
            )
            assertThat(columns).contains("assigned_at", "persistence_version", "version", "round_id", "offer_id")
            val indexes = jdbc.queryForList("select indexname from pg_indexes where schemaname=current_schema() and tablename='work_orders'", String::class.java)
            assertThat(indexes).contains("uq_work_orders_active_driver", "uq_work_orders_active_request")
        }
    }

    @Test
    fun `기존 작업에 연결할 배차 이력이 없으면 데이터와 마이그레이션 이력을 보존한다`() {
        withMainSchema { jdbc, _, flyway ->
            jdbc.insertDriverFixture(10)
            jdbc.update("insert into work_orders (request_id, driver_id, status, created_at) values (1,10,'NOT_STARTED',current_timestamp)")

            val failure = assertThrows<FlywayException> { flyway("latest").migrate() }

            assertThat(failure).hasStackTraceContaining("Existing work orders require an explicit dispatch history mapping")
            assertThat(jdbc.queryForObject("select count(*) from work_orders where driver_id=10", Long::class.java)).isEqualTo(1)
            assertThat(jdbc.queryForObject("select max(version::int) from flyway_schema_history where success and version is not null", Int::class.java)).isEqualTo(17)
        }
    }

    private fun withMainSchema(test: (JdbcTemplate, String, (String) -> Flyway) -> Unit) {
        val schema = "operations_upgrade_" + UUID.randomUUID().toString().replace("-", "")
        val base = System.getenv("TEST_DATABASE_URL") ?: "jdbc:postgresql://localhost:15432/allcheck"
        val source = DriverManagerDataSource(base + (if ('?' in base) "&" else "?") + "currentSchema=$schema", System.getenv("TEST_DATABASE_USERNAME") ?: "allcheck", System.getenv("TEST_DATABASE_PASSWORD") ?: "allcheck_local_6")
        val jdbc = JdbcTemplate(source)
        fun flyway(target: String) = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target(target).load()
        try {
            flyway("17").migrate()
            test(jdbc, schema, ::flyway)
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }
}
