package com.allcheck.reception.application

import com.allcheck.reception.ReceptionPushDevices
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
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

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = ["reception.push.enabled"], havingValue = "true")
class ReceptionPushWorkerConfig(
    devices: ObjectProvider<ReceptionPushDevices>,
    @Value("\${notification.fcm.enabled:false}") fcmEnabled: Boolean,
    @Value("\${reception.push.claim-ttl}") ttl: Duration,
    @Value("\${reception.push.poll-interval}") pollInterval: Duration,
    @Value("\${notification.fcm.request-timeout}") timeout: Duration,
) {
    init {
        require(fcmEnabled && devices.ifAvailable != null) { "FCM and Identity device integration are required before enabling reception push." }
        require(ttl > timeout && timeout > Duration.ZERO && pollInterval > Duration.ZERO) { "Push claim TTL must exceed the HTTP timeout; runtime intervals must be positive." }
    }

    @Bean
    fun receptionPushExecutor() = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 1
        queueCapacity = 0
        setThreadNamePrefix("reception-push-")
    }
}

@Component
@ConditionalOnProperty(name = ["reception.push.enabled"], havingValue = "true")
class ReceptionPushWorker(
    private val transactions: ReceptionPushTransactions,
    private val service: ReceptionPushService,
    @Qualifier("receptionPushExecutor") private val executor: ThreadPoolTaskExecutor,
    @Value("\${reception.push.claim-ttl}") private val ttl: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${reception.push.poll-interval}")
    fun poll() = databaseBoundary {
        try {
            executor.execute {
                databaseBoundary {
                    transactions.pendingEvents(1).forEach(service::expand)
                    transactions.due(1).forEach { service.send(it, ttl) }
                }
            }
        } catch (_: TaskRejectedException) {
            // DB에 남은 PENDING은 현재 전송이 끝난 뒤 다음 조회에서 처리한다.
        }
    }

    @Scheduled(fixedDelayString = "\${reception.push.poll-interval}")
    fun expire() = databaseBoundary {
        transactions.overdue(20).forEach {
            try {
                transactions.expire(it)
            } catch (_: ObjectOptimisticLockingFailureException) {
                // 다른 작업자의 결과 기록이 먼저 커밋됐다. 다음 조회에서 현재 상태를 따른다.
            }
        }
    }

    private fun databaseBoundary(action: () -> Unit) {
        try {
            action()
        } catch (failure: DataAccessException) {
            log.error("Reception push database operation failed: type={}", failure.javaClass.simpleName)
        } catch (failure: TransactionException) {
            log.error("Reception push transaction failed: type={}", failure.javaClass.simpleName)
        }
    }
}
