package com.allcheck.reception.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskRejectedException
import org.springframework.dao.DataAccessException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionException
import java.time.Duration
import java.util.UUID

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = ["reception.worker.enabled"], havingValue = "true")
class ReceptionAnalysisWorkerConfig(
    @Value("\${reception.worker.concurrency}") private val concurrency: Int,
    @Value("\${reception.worker.execution-budget}") private val budget: Duration,
    @Value("\${reception.worker.poll-interval}") private val poll: Duration,
    @Value("\${reception.ai.enabled:false}") private val aiEnabled: Boolean,
    @Value("\${reception.storage.enabled:false}") private val storageEnabled: Boolean,
) {
    init {
        require(concurrency > 0 && budget > Duration.ZERO && poll > Duration.ZERO) { "Explicit positive analysis runtime settings are required." }
        require(aiEnabled && storageEnabled) { "AI and recording storage must be configured before enabling the worker." }
    }

    @Bean
    fun receptionAnalysisExecutor() = ThreadPoolTaskExecutor().apply {
        corePoolSize = concurrency
        maxPoolSize = concurrency
        queueCapacity = 0 // 실행 대기열은 DB의 PENDING 시도이며 메모리에 따로 적재하지 않는다.
        setThreadNamePrefix("reception-analysis-")
    }
}

@Component
@ConditionalOnProperty(name = ["reception.worker.enabled"], havingValue = "true")
class ReceptionAnalysisWorker(
    private val transactions: ReceptionAnalysisTransactions,
    private val service: ReceptionAnalysisService,
    @Qualifier("receptionAnalysisExecutor") private val executor: ThreadPoolTaskExecutor,
    @Value("\${reception.worker.concurrency}") private val concurrency: Int,
    @Value("\${reception.worker.execution-budget}") private val budget: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${reception.worker.poll-interval}")
    fun poll() = databaseBoundary {
        for (id in transactions.pending(concurrency)) {
            try {
                executor.execute { databaseBoundary { service.execute(id, budget) } }
            } catch (_: TaskRejectedException) {
                break // 아직 claim하지 않은 PENDING은 다음 조회에서 처음 실행한다.
            }
        }
    }

    @Scheduled(fixedDelayString = "\${reception.worker.poll-interval}")
    fun expire() = databaseBoundary {
        for (id in transactions.overdue(concurrency)) {
            try {
                transactions.expire(id, UUID.randomUUID().toString())
            } catch (_: ObjectOptimisticLockingFailureException) {
                // 해당 시도가 진전했거나 종료됐다. 다음 조회와 단계별 기한 검사에서 다시 판정한다.
            }
        }
    }

    private fun databaseBoundary(action: () -> Unit) {
        try {
            action()
        } catch (failure: DataAccessException) {
            log.error("Reception analysis database operation failed: type={}", failure.javaClass.simpleName)
        } catch (failure: TransactionException) {
            log.error("Reception analysis transaction failed: type={}", failure.javaClass.simpleName)
        }
    }
}
