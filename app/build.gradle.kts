plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

/** Значение из переменной окружения или Gradle-свойства, безопасно для вставки в строку BuildConfig. */
fun secret(name: String): String =
    (System.getenv(name) ?: (project.findProperty(name) as String?) ?: "")
        .replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.example.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Адрес Worker и общий токен приложения приходят из переменных окружения (в GitHub Actions — из Secrets)
        // или из -PWORKER_URL=... при локальной сборке. Ключа Groq в приложении нет: он хранится только в Worker.
        buildConfigField("String", "WORKER_URL", "\"${secret("WORKER_URL")}\"")
        buildConfigField("String", "APP_TOKEN", "\"${secret("APP_TOKEN")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
