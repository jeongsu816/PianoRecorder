plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.pianorecorder"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "app.pianorecorder"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "1.3"
    }

    // 릴리스 서명 정보는 저장소에 두지 않고 환경변수로만 받는다 (scripts/release.sh가 Vaultwarden에서 꺼내 넣음).
    // 환경변수가 없으면 release 서명 설정을 만들지 않으므로, 서명 없이는 릴리스 APK가 나오지 않는다.
    val releaseKeystore = providers.environmentVariable("PIANORECORDER_KEYSTORE").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("PIANORECORDER_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("PIANORECORDER_KEY_ALIAS").getOrElse("pianorecorder")
                keyPassword = storePassword // PKCS12는 키스토어와 키의 비밀번호가 같다
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")

    testImplementation("junit:junit:4.13.2")
}
