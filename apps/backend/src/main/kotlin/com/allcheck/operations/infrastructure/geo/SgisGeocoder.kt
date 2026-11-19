package com.allcheck.operations.infrastructure.geo

import com.allcheck.global.exception.BusinessException
import com.allcheck.operations.OperationsGeocoder
import com.allcheck.operations.SiteCoordinates
import com.allcheck.operations.exception.OperationsErrorCode
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration
import java.time.Instant

class SgisConnection(val client: RestClient, val serviceId: String, val secret: String, private val http: HttpClient? = null) : AutoCloseable {
    override fun close() {
        http?.close()
    }
}

@Configuration(proxyBeanMethods = false)
class SgisConfig {
    @Bean
    @ConditionalOnProperty(name = ["operations.sgis.enabled"], havingValue = "true")
    fun sgisConnection(
        @Value("\${operations.sgis.service-id}") serviceId: String,
        @Value("\${operations.sgis.secret}") secret: String,
        @Value("\${operations.sgis.connect-timeout}") connectTimeout: Duration,
        @Value("\${operations.sgis.request-timeout}") requestTimeout: Duration,
    ): SgisConnection {
        require(serviceId.isNotBlank() && secret.isNotBlank() && connectTimeout.isPositive && requestTimeout.isPositive)
        val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(connectTimeout).build()
        val factory = JdkClientHttpRequestFactory(http).apply { setReadTimeout(requestTimeout) }
        return SgisConnection(RestClient.builder().baseUrl("https://sgisapi.kostat.go.kr/OpenAPI3").requestFactory(factory).build(), serviceId, secret, http)
    }
}

/** SGIS는 EPSG:5179를 반환한다. 건물 수준의 단일 완전 일치만 채택하고 PostGIS로 WGS84로 변환한다. */
@Component
class SgisGeocoder(private val connections: ObjectProvider<SgisConnection>, private val spatial: OperationsSpatial, private val clock: Clock) : OperationsGeocoder {
    private val mapper = JsonMapper.builder().build()
    private var cachedToken: String? = null
    private var tokenExpiry: Instant = Instant.MIN

    override fun resolve(address: String): SiteCoordinates {
        val connection = connections.ifAvailable ?: throw BusinessException(OperationsErrorCode.GEOCODER_UNAVAILABLE)
        try {
            val token = token(connection)
            val response = call(connection.client.get().uri { it.path("/addr/geocode.json").queryParam("accessToken", token).queryParam("address", address).queryParam("pagenum", 0).queryParam("resultcount", 2).build() })
            if (response.path("errCd").asInt(Int.MIN_VALUE) == -100) throw BusinessException(OperationsErrorCode.SITE_NOT_RESOLVED)
            if (response.path("errCd").asInt(Int.MIN_VALUE) != 0) throw BusinessException(OperationsErrorCode.GEOCODER_UNAVAILABLE)
            val result = response.path("result")
            val rows = result.path("resultdata")
            if (result.path("totalcount").asString("") != "1" || result.path("matching").asString("") != "1" || !rows.isArray || rows.size() != 1) throw BusinessException(OperationsErrorCode.SITE_NOT_RESOLVED)
            val row = rows[0]
            if (row.path("addr_type").asString("") != "6") throw BusinessException(OperationsErrorCode.SITE_NOT_RESOLVED)
            fun text(field: String) = row.path(field).asString("").takeIf { it.isNotBlank() && it != "null" }
            val area = listOfNotNull(text("sido_nm"), text("sgg_nm"), text("leg_nm") ?: text("adm_nm")).joinToString(" ")
            if (text("sgg_nm") == null || (text("leg_nm") == null && text("adm_nm") == null)) throw BusinessException(OperationsErrorCode.SITE_NOT_RESOLVED)
            val x = text("x")?.toDoubleOrNull() ?: throw BusinessException(OperationsErrorCode.SITE_NOT_RESOLVED)
            val y = text("y")?.toDoubleOrNull() ?: throw BusinessException(OperationsErrorCode.SITE_NOT_RESOLVED)
            return spatial.fromUtmK(x, y, area)
        } catch (failure: BusinessException) {
            throw failure
        } catch (_: Exception) {
            // 원격 예외의 URI에는 주소·토큰·서비스 Secret이 들어갈 수 있다.
            throw BusinessException(OperationsErrorCode.GEOCODER_UNAVAILABLE)
        }
    }

    @Synchronized
    private fun token(connection: SgisConnection): String {
        cachedToken?.takeIf { clock.instant().plusSeconds(30) < tokenExpiry }?.let { return it }
        val response = call(connection.client.get().uri { it.path("/auth/authentication.json").queryParam("consumer_key", connection.serviceId).queryParam("consumer_secret", connection.secret).build() })
        if (response.path("errCd").asInt(Int.MIN_VALUE) != 0) throw BusinessException(OperationsErrorCode.GEOCODER_UNAVAILABLE)
        val result = response.path("result")
        val token = result.path("accessToken").asString("")
        val expiry = result.path("accessTimeout").asString("").toLongOrNull() ?: throw BusinessException(OperationsErrorCode.GEOCODER_UNAVAILABLE)
        if (token.isBlank()) throw BusinessException(OperationsErrorCode.GEOCODER_UNAVAILABLE)
        // epoch seconds와 epoch milliseconds 응답을 모두 만료 시각으로 정규화한다.
        tokenExpiry = if (expiry >= 100_000_000_000L) Instant.ofEpochMilli(expiry) else Instant.ofEpochSecond(expiry)
        if (tokenExpiry <= clock.instant()) throw BusinessException(OperationsErrorCode.GEOCODER_UNAVAILABLE)
        cachedToken = token
        return token
    }
    private fun call(request: RestClient.RequestHeadersSpec<*>): JsonNode {
        val response = request.retrieve().toEntity(ByteArray::class.java)
        if (response.statusCode.value() != 200) throw BusinessException(OperationsErrorCode.GEOCODER_UNAVAILABLE)
        return mapper.readTree(response.body ?: byteArrayOf())
    }
}
