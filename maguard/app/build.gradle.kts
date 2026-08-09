plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.uma.maguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.uma.maguard"
        minSdk = 26        // Android 8.0以降。アクセシビリティAPIが安定して使えるライン
        targetSdk = 34
        versionCode = 1
        versionName = "0.7.6"
    }

    // debug ビルドの署名鍵を固定する。
    //
    // 指定しない場合、Gradle は実行環境ごとに ~/.android/debug.keystore を
    // 自動生成する。CI（GitHub Actions）は毎回まっさらな使い捨て環境なので、
    // ビルドのたびに違う鍵で署名された APK ができてしまい、
    // 「以前インストールした debug ビルドの上に、新しい debug ビルドを
    // 上書きインストールできない（署名が一致しない）」という問題が起きる。
    //
    // debug 用の鍵はリリース鍵と違い、流出しても「このアプリの偽の更新版を
    // 配布できる」というリスクにはならない（Play ストアなどの配布先は
    // リリース鍵でしか検証しないため）。プロジェクトに含めて固定するのが一般的。
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
