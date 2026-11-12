plugins { id("com.android.application"); id("dev.flutter.flutter-gradle-plugin") }
android {
 namespace="kr.allcheck.flutter.driver"
 compileSdk=flutter.compileSdkVersion
 ndkVersion=flutter.ndkVersion
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 defaultConfig { applicationId="kr.allcheck.flutter.driver"; minSdk=24; targetSdk=flutter.targetSdkVersion; versionCode=flutter.versionCode; versionName=flutter.versionName }
}
kotlin { compilerOptions { jvmTarget=org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
flutter { source="../.." }
