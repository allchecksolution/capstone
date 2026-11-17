package com.allcheck.driver.infrastructure.push

import com.allcheck.identity.PushTarget
import com.google.api.client.json.gson.GsonFactory
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import tools.jackson.databind.json.JsonMapper

class FcmClientTest {
    private val sender = Mockito.mock(FirebaseMessaging::class.java)
    private val factory = DefaultListableBeanFactory().apply { registerSingleton("sender", sender) }
    private val client = FcmClient(factory.getBeanProvider(FirebaseMessaging::class.java))
    private val target = PushTarget("private-account-id", "installation", "test-device-registration-token", "digest")
    private val data = FcmData("event", "DRIVER_APPROVED", "driver", 1)

    @Test
    fun `FCM SDK에 등록 토큰과 식별자만 보내고 제공자 접수 ID를 반환한다`() {
        // given
        Mockito.`when`(sender.send(anyMessage())).thenReturn("projects/test/messages/accepted")

        // when
        val result = client.send(target, data)

        // then
        val argument = ArgumentCaptor.forClass(Message::class.java)
        Mockito.verify(sender).send(argument.capture() ?: message())
        val json = JsonMapper.builder().build().readTree(GsonFactory.getDefaultInstance().toString(argument.value))
        assertThat(json.path("token").stringValue()).isEqualTo(target.token)
        assertThat(json.path("data").size()).isEqualTo(4)
        assertThat(json.path("data").path("eventId").stringValue()).isEqualTo("event")
        assertThat(json.toString()).doesNotContain(target.accountId, "rejectionReason", "accountNumber", "representativeName")
        assertThat(result.messageId).isEqualTo("projects/test/messages/accepted")
        assertThat(result.errorCode).isNull()
    }

    @ParameterizedTest
    @EnumSource(value = MessagingErrorCode::class, names = ["UNREGISTERED", "UNAVAILABLE", "INVALID_ARGUMENT", "SENDER_ID_MISMATCH"])
    fun `무효 등록 토큰만 폐기 대상으로 분류하고 원본 예외를 노출하지 않는다`(code: MessagingErrorCode) {
        // given
        val failure = Mockito.mock(FirebaseMessagingException::class.java)
        Mockito.`when`(failure.messagingErrorCode).thenReturn(code)
        Mockito.`when`(sender.send(anyMessage())).thenThrow(failure)

        // when
        val result = client.send(target, data)

        // then
        assertThat(result.errorCode).isEqualTo(code.name)
        assertThat(result.invalidToken).isEqualTo(code == MessagingErrorCode.UNREGISTERED)
        assertThat(result.messageId).isNull()
    }

    @Test
    fun `Firebase가 설정되지 않았을 때 전송 성공으로 처리하지 않는다`() {
        // given
        val unconfigured = FcmClient(DefaultListableBeanFactory().getBeanProvider(FirebaseMessaging::class.java))

        // when & then
        assertThat(unconfigured.isConfigured()).isFalse()
        assertThat(unconfigured.send(target, data).errorCode).isEqualTo("NOT_CONFIGURED")
    }

    @Suppress("DEPRECATION")
    private fun message(): Message = Message.builder().setToken("test-token").build()
    private fun anyMessage(): Message = any(Message::class.java) ?: message()
}
