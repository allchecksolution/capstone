package com.allcheck.driver.application.notification

import com.allcheck.global.notification.FcmClient
import com.allcheck.global.notification.FcmException
import com.allcheck.global.notification.FcmFailure
import com.allcheck.global.notification.FcmNotification
import com.allcheck.identity.PushTarget
import org.springframework.stereotype.Component

/** 기사 알림 구성과 결과 해석을 담당하고, 연결과 HTTP 전송은 공통 FCM 클라이언트에 위임한다. */
@Component
class DriverPushSender(private val fcm: FcmClient) {
    fun isConfigured(): Boolean = fcm.isConfigured()

    fun send(target: PushTarget, data: DriverPushData, beforeSend: () -> Boolean = { true }): DriverPushResult? {
        val payload = mapOf(
            "eventId" to data.eventId.toString(),
            "eventType" to data.eventType,
            "driverId" to data.driverId.toString(),
            "revision" to data.revision.toString(),
        )
        return try {
            val messageId = fcm.send(
                target.token,
                payload,
                FcmNotification("기사 가입 상태 안내", "앱에서 최신 가입 신청 상태를 확인해 주세요."),
                beforeSend,
            )
            DriverPushResult(messageId = messageId)
        } catch (failure: FcmException) {
            if (failure.failure == FcmFailure.CANCELLED) return null
            DriverPushResult(errorCode = failure.failure.name, invalidToken = failure.failure == FcmFailure.UNREGISTERED)
        }
    }
}
