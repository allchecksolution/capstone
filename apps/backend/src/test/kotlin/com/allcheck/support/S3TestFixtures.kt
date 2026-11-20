package com.allcheck.support

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.lang.reflect.Proxy

object S3TestFixtures {
    val DIGEST = ByteArray(32) { 1 }

    fun signer(onResolve: () -> Unit = {}): S3Presigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
        .credentialsProvider(
            AwsCredentialsProvider {
                onResolve()
                AwsBasicCredentials.create("test-access-key", "test-secret-key")
            },
        ).build()

    fun fakeS3(header: (GetObjectRequest) -> ByteArray = { "RIFF0000WAVE".toByteArray(Charsets.US_ASCII) }, head: (HeadObjectRequest) -> HeadObjectResponse): S3Client = Proxy.newProxyInstance(S3Client::class.java.classLoader, arrayOf(S3Client::class.java)) { proxy, method, arguments ->
        when (method.name) {
            "headObject" -> head(arguments[0] as HeadObjectRequest)
            "getObjectAsBytes" -> ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), header(arguments[0] as GetObjectRequest))
            "close" -> null
            "serviceName" -> "s3"
            "toString" -> "OfflineTestS3"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments[0]
            else -> error("Unexpected S3 test operation.")
        }
    } as S3Client
}
