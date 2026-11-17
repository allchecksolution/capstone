package com.allcheck.driver.infrastructure.push

import com.allcheck.identity.PushTarget
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

data class FcmData(val eventId: String, val eventType: String, val driverId: String, val revision: Long)
class FcmSendResult(val messageId: String? = null, val errorCode: String? = null, val invalidToken: Boolean = false)

@Component
class FcmClient(private val messaging: ObjectProvider<FirebaseMessaging>) {
    fun isConfigured(): Boolean = messaging.ifAvailable != null

    // 확정된 pushToken 계약은 FCM 등록 토큰이다. FID와 혼동하지 않고 SDK의 호환 API를 사용한다.
    @Suppress("DEPRECATION")
    fun send(target: PushTarget, data: FcmData): FcmSendResult {
        val sender = messaging.ifAvailable ?: return FcmSendResult(errorCode = "NOT_CONFIGURED")
        val message = Message.builder().setToken(target.token)
            .putData("eventId", data.eventId).putData("eventType", data.eventType)
            .putData("driverId", data.driverId).putData("revision", data.revision.toString())
            .setNotification(Notification.builder().setTitle("기사 가입 상태 안내").setBody("앱에서 최신 가입 신청 상태를 확인해 주세요.").build())
            .build()
        return try {
            FcmSendResult(messageId = sender.send(message))
        } catch (ex: FirebaseMessagingException) {
            // 토큰·개인정보를 포함할 수 있는 원본 메시지나 cause를 기록하지 않는다.
            FcmSendResult(errorCode = ex.messagingErrorCode?.name ?: "FCM_ERROR", invalidToken = ex.messagingErrorCode == MessagingErrorCode.UNREGISTERED)
        }
    }
}
