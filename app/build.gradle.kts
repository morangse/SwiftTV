import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionName = providers.gradleProperty("swifttv.versionName").getOrElse("0.3.0")
val appVersionCode = providers.gradleProperty("swifttv.versionCode").map { it.toInt() }.getOrElse(9)

android {
    namespace = "app.swifttv.player"
    compileSdk = 36
    buildFeatures { buildConfig = true }
    defaultConfig {
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            applicationId = if (providers.gradleProperty("isolatedUiCheck").orNull == "true") "app.swifttv.player.uicheck" else "app.swifttv.player"
            minSdk = 23
            targetSdk = 36
            buildConfigField("boolean", "IS_LITE", "false")
            manifestPlaceholders["appLabel"] = "SwiftTV"
        }
        create("lite") {
            dimension = "edition"
            applicationId = if (providers.gradleProperty("isolatedUiCheck").orNull == "true") "app.swifttv.player.lite.uicheck" else "app.swifttv.player.lite"
            minSdk = 19
            targetSdk = 36
            multiDexEnabled = true
            buildConfigField("boolean", "IS_LITE", "true")
            manifestPlaceholders["appLabel"] = "SwiftTV Lite"
        }
    }
    val releaseProperties = Properties()
    val signingDir = providers.gradleProperty("swifttv.signingDir")
        .map { rootProject.file(it) }.getOrElse(rootProject.file(".signing"))
    val releasePropertiesFile = signingDir.resolve("signing.properties")
    if (releasePropertiesFile.exists()) releasePropertiesFile.inputStream().use { releaseProperties.load(it) }
    signingConfigs {
        if (releasePropertiesFile.exists()) create("localRelease") {
            storeFile = signingDir.resolve("swifttv-release.p12")
            storePassword = releaseProperties.getProperty("storePassword")
            keyAlias = "swifttv"
            keyPassword = releaseProperties.getProperty("storePassword")
            storeType = "PKCS12"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releasePropertiesFile.exists()) signingConfig = signingConfigs.getByName("localRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        lintConfig = file("lint.xml")
        abortOnError = true
    }
}

dependencies {
    "standardImplementation"("androidx.core:core-ktx:1.16.0")
    "standardImplementation"("androidx.activity:activity-ktx:1.11.0")
    "standardImplementation"("androidx.recyclerview:recyclerview:1.3.0")
    "standardImplementation"("androidx.media3:media3-exoplayer:1.11.1")
    "standardImplementation"("androidx.media3:media3-exoplayer-hls:1.11.1")
    "standardImplementation"("androidx.media3:media3-exoplayer-rtsp:1.11.1")
    "standardImplementation"("androidx.media3:media3-datasource-okhttp:1.11.1")
    "standardImplementation"("androidx.media3:media3-datasource-rtmp:1.11.1")
    "standardImplementation"("androidx.media3:media3-ui:1.11.1")
    "standardImplementation"("com.squareup.okhttp3:okhttp:4.12.0")
    "testStandardImplementation"("com.squareup.okhttp3:mockwebserver:4.12.0")

    "liteImplementation"("androidx.multidex:multidex:2.0.1")
    "liteImplementation"("androidx.core:core-ktx:1.12.0")
    "liteImplementation"("androidx.activity:activity-ktx:1.7.2")
    "liteImplementation"("androidx.recyclerview:recyclerview:1.3.0")
    "liteImplementation"("androidx.media3:media3-exoplayer:1.4.1")
    "liteImplementation"("androidx.media3:media3-exoplayer-hls:1.4.1")
    "liteImplementation"("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    "liteImplementation"("androidx.media3:media3-datasource-rtmp:1.4.1")
    "liteImplementation"("androidx.media3:media3-ui:1.4.1")
    "liteImplementation"("com.squareup.okhttp3:okhttp:3.12.13")
    "testLiteImplementation"("com.squareup.okhttp3:mockwebserver:3.12.13")

    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

tasks.register<JavaExec>("pairingPreview") {
    dependsOn("compileStandardDebugUnitTestKotlin")
    classpath = files(layout.buildDirectory.dir("tmp/kotlin-classes/standardDebug"), layout.buildDirectory.dir("tmp/kotlin-classes/standardDebugUnitTest")) + configurations.getByName("standardDebugUnitTestRuntimeClasspath")
    mainClass.set("app.swifttv.player.PairingPreviewMain")
    workingDir = rootProject.projectDir
}
