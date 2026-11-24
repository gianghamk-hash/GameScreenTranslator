plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.giang.gametranslator"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.giang.gametranslator"
        minSdk = 33
        targetSdk = 37
        versionCode = 30001
        versionName = "3.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                // Compatibility hotfix: match the unoptimized V1 debug runtime.
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // 1. Google ML Kit nhận diện chữ tiếng Anh
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")

    // 2. OkHttp để gọi Gemini API dịch siêu nhanh
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}