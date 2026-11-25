plugins {
    alias(libs.plugins.android.application)
}

val propVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 5
val propVersionName = (project.findProperty("versionName") as String?) ?: "2.3"
val keystorePath = System.getenv("KEYSTORE_PATH")
val keystorePasswordEnv = System.getenv("KEYSTORE_PASSWORD")
val keyAliasEnv = System.getenv("KEY_ALIAS")
val keyPasswordEnv = System.getenv("KEY_PASSWORD")

android {
    namespace = "com.giang.gametranslator"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.giang.gametranslator"
        minSdk = 33
        targetSdk = 37
        versionCode = propVersionCode
        versionName = propVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (!keystorePath.isNullOrBlank() &&
            !keystorePasswordEnv.isNullOrBlank() &&
            !keyAliasEnv.isNullOrBlank() &&
            !keyPasswordEnv.isNullOrBlank()
        ) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePasswordEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let {
                signingConfig = it
            }
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
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

    // 1. Google ML Kit nhận diện chữ tiếng Anh (OCR)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // 2. Google ML Kit dịch Offline ngay trên thiết bị (EN -> VI)
    implementation("com.google.mlkit:translate:17.0.3")

    // 3. OkHttp để gọi Gemini API dịch siêu nhanh (HTTP/2 connection pooling)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}