@file:Suppress("DEPRECATION")
import java.util.Base64
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("dev.flutter.flutter-gradle-plugin") }
val dartDefines = (project.findProperty("dart-defines") as? String).orEmpty()
 .split(",").filter { it.isNotBlank() }
 .map { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
 .associate { it.substringBefore("=") to it.substringAfter("=", "") }
val kakaoNativeAppKey = dartDefines["KAKAO_NATIVE_APP_KEY"].orEmpty()
require(kakaoNativeAppKey.isEmpty() || kakaoNativeAppKey.matches(Regex("[a-fA-F0-9]{32}"))) {
 "KAKAO_NATIVE_APP_KEY must be the 32-character native app key"
}
android {
 namespace="kr.allcheck.flutter.driver"
 compileSdk=flutter.compileSdkVersion
 ndkVersion=flutter.ndkVersion
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 defaultConfig { applicationId="kr.allcheck.flutter.driver"; minSdk=24; targetSdk=flutter.targetSdkVersion; versionCode=flutter.versionCode; versionName=flutter.versionName; manifestPlaceholders["kakaoNativeAppKey"]=kakaoNativeAppKey.ifEmpty { "unconfigured" } }
}
kotlin { compilerOptions { jvmTarget=org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
flutter { source="../.." }

if(file("google-services.json").exists()) { apply(plugin="com.google.gms.google-services") }
