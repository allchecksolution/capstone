@file:Suppress("DEPRECATION")
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("dev.flutter.flutter-gradle-plugin") }
android {
 sourceSets.getByName("main").java.srcDir("../../../shared/android")
 namespace="kr.allcheck.flutter.admin"
 compileSdk=flutter.compileSdkVersion
 ndkVersion=flutter.ndkVersion
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 defaultConfig { applicationId="kr.allcheck.flutter.admin"; minSdk=24; targetSdk=flutter.targetSdkVersion; versionCode=flutter.versionCode; versionName=flutter.versionName }
}
kotlin { compilerOptions { jvmTarget=org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
flutter { source="../.." }
dependencies {
 implementation("androidx.work:work-runtime:2.10.5")
 testImplementation("junit:junit:4.13.2")
}

if(file("google-services.json").exists()) { apply(plugin="com.google.gms.google-services") }
