import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.example.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 37
        versionName = "1.37"
        // Только телефонные процессоры: без x86 приложение заметно легче
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // Метка сборки видна в приложении: ветка и короткий номер коммита (в CI берутся из GitHub)
        buildConfigField("String", "BUILD_TAG", "\"${(System.getenv("GITHUB_REF_NAME") ?: "local").substringAfterLast('/')} ${(System.getenv("GITHUB_SHA") ?: "").take(7)}\"")
        buildConfigField("String", "GROQ_API_KEY", "\"${System.getenv("GROQ_API_KEY") ?: ""}\"")
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    buildFeatures { buildConfig = true }

    // Модель весит около 130 МБ и почти не сжимается — не тратим время на упаковку
    androidResources { noCompress += listOf("onnx") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-1.13.8.aar")
tasks.register("downloadSherpaAar") {
    outputs.file(sherpaAar)
    doLast {
        val target = sherpaAar.asFile
        if (!target.exists() || target.length() < 1_000_000L) {
            target.parentFile.mkdirs()
            val temp = target.resolveSibling(target.name + ".part")
            URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar").openStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            check(temp.length() > 1_000_000L) { "Sherpa-ONNX AAR download failed or was incomplete" }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn("downloadSherpaAar") }

dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("commons-io:commons-io:2.17.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
