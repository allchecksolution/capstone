package com.allcheck.global.ratelimit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    properties = [
        "spring.datasource.url=\${IDENTITY_TEST_DATABASE_URL:jdbc:postgresql://localhost:15432/allcheck_identity_test}",
        "spring.datasource.username=\${IDENTITY_TEST_DATABASE_USER:allcheck}",
        "spring.datasource.password=\${IDENTITY_TEST_DATABASE_PASSWORD:allcheck_local_6}",
        "spring.jpa.show-sql=false",
    ],
)
@TestPropertySource("classpath:jwt-test.properties")
class RequestLimitIntegrationTest @Autowired constructor(
    private val limits: RequestLimitService,
    private val jdbc: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val ownSubjects = mutableListOf<String>()

    @AfterEach
    fun removeOwnCounters() {
        ownSubjects.forEach { jdbc.update("delete from request_limit_buckets where bucket_id like ?", "%:$it") }
    }

    @Test
    fun `동시 요청이 같은 DB 카운터를 사용하여 허용 횟수를 초과하지 않는다`() {
        // given
        val subject = subject()
        val policy = RequestLimitPolicy("test-concurrent", 5, Duration.ofMinutes(1))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)

        // when
        try {
            val requests = (1..40).map {
                executor.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    allowed(subject, policy)
                }
            }
            start.countDown()
            val outcomes = requests.map { it.get(15, TimeUnit.SECONDS) }

            // then
            assertThat(outcomes.count { it }).isEqualTo(5)
            assertThat(outcomes.count { !it }).isEqualTo(35)
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `차단 요청은 만료를 연장하지 않고 만료된 카운터는 다음 요청에서 재사용한다`() {
        // given
        val subject = subject()
        val policy = RequestLimitPolicy("test-expiry", 2, Duration.ofMinutes(1))
        repeat(2) { assertThat(allowed(subject, policy)).isTrue() }
        val bucket = "${policy.scope}:$subject"
        val endsAt = jdbc.queryForObject("select window_ends_at from request_limit_buckets where bucket_id = ?", Timestamp::class.java, bucket)

        // when & then
        repeat(3) { assertThat(allowed(subject, policy)).isFalse() }
        assertThat(jdbc.queryForObject("select window_ends_at from request_limit_buckets where bucket_id = ?", Timestamp::class.java, bucket)).isEqualTo(endsAt)
        jdbc.update("update request_limit_buckets set window_ends_at = statement_timestamp() - interval '1 second' where bucket_id = ?", bucket)
        assertThat(allowed(subject, policy)).isTrue()
        assertThat(jdbc.queryForObject("select request_count from request_limit_buckets where bucket_id = ?", Int::class.java, bucket)).isEqualTo(1)
    }

    @Test
    fun `업무 트랜잭션 롤백과 다른 계정이나 API 한도는 집계에 영향을 주지 않는다`() {
        // given
        val first = subject()
        val other = subject()
        val policy = RequestLimitPolicy("test-isolation", 1, Duration.ofMinutes(1))

        // when
        transactions.executeWithoutResult {
            limits.check(first, policy)
            it.setRollbackOnly()
        }

        // then
        assertThat(allowed(first, policy)).isFalse()
        assertThat(allowed(other, policy)).isTrue()
        assertThat(allowed(first, policy.copy(scope = "test-other-api"))).isTrue()
    }

    private fun subject(): String = UUID.randomUUID().toString().also { ownSubjects.add(it) }

    private fun allowed(subject: String, policy: RequestLimitPolicy): Boolean = try {
        limits.check(subject, policy)
        true
    } catch (exception: RequestLimitExceededException) {
        assertThat(exception.retryAfterSeconds).isBetween(1L, 60L)
        false
    }
}
