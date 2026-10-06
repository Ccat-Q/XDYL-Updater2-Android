import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}
val signing = Properties().apply {
    val file = rootProject.file(".local/signing/signing.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = System.getenv(env) ?: signing.getProperty(key)
val releaseKey = signingValue("storeFile", "ANDROID_KEYSTORE_FILE")
android {
    namespace = "com.ccatq.xdylupdater2"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ccatq.xdylupdater2"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("starwaveVersionCode").orNull?.toInt() ?: 1
        versionName = "2.1.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = rootProject.file(releaseKey)
            storePassword = signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "ANDROID_KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "ANDROID_KEY_PASSWORD")
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui); implementation(libs.compose.preview)
    implementation(libs.compose.material3); implementation(libs.compose.icons)
    implementation(libs.activity.compose); implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel); implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose); implementation(libs.room.runtime)
    implementation(libs.room.ktx); ksp(libs.room.compiler)
    implementation(libs.work.runtime); implementation(libs.datastore)
    implementation(libs.okhttp); implementation(libs.serialization)
    implementation(libs.coroutines); implementation(libs.coil)
    implementation(libs.core.ktx)
    debugImplementation(libs.compose.tooling); debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.junit); testImplementation(libs.mockwebserver); testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom)); androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.android.test.runner); androidTestImplementation(libs.android.test.junit)
}
tasks.register("checkReleaseSigning") {
    doLast { check(releaseKey != null) { "Release signing is missing; configure .local/signing or ANDROID_KEYSTORE_* environment variables." } }
}
tasks.matching { it.name == "packageRelease" }.configureEach { dependsOn("checkReleaseSigning") }
