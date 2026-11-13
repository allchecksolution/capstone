pluginManagement {
 val props=java.util.Properties()
 file("local.properties").inputStream().use { props.load(it) }
 val flutterSdkPath=props.getProperty("flutter.sdk") ?: error("flutter.sdk must be configured")
 includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")
 repositories { google(); mavenCentral(); gradlePluginPortal() }
}
plugins {
 id("dev.flutter.flutter-plugin-loader") version "1.0.0"
 id("com.google.gms.google-services") version "4.4.4" apply false
 id("com.android.application") version "9.3.1" apply false
}
include(":app")
