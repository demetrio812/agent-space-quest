plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.agentspace.quest"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.agentspace.quest"
        minSdk = 34
        targetSdk = 34
        versionCode = 19
        versionName = "0.11.0-terminal-input"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs.getByName("debug") {
        storeFile = file(providers.gradleProperty("agentspaceDebugKeystore")
            .orElse("${gradle.gradleUserHomeDir}/agentspace-debug.jks").get())
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
    sourceSets.getByName("main").java.srcDir("src/main/third_party/termux")
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/LICENSE", "META-INF/NOTICE", "META-INF/AL2.0", "META-INF/LGPL2.1") }
    lint { disable += "ExpiredTargetSdkVersion" }
}

dependencies {
    implementation("com.github.mwiede:jsch:2.28.0")
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.meta.spatial:meta-spatial-sdk:0.14.0")
    implementation("com.meta.spatial:meta-spatial-sdk-toolkit:0.14.0")
    implementation("com.meta.spatial:meta-spatial-sdk-vr:0.14.0")
    implementation("com.meta.spatial:meta-spatial-sdk-isdk:0.14.0")
    implementation("com.meta.spatial:meta-spatial-sdk-compose:0.14.0")
    testImplementation("junit:junit:4.13.2")
}
