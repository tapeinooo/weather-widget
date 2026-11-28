plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 版の番号は GitHub Actions の実行番号から自動で決める（共通ルール）。
// このアプリは手で番号を付けていた頃（v1.25・versionCode 26）より大きくするため、
// versionCode に versionOffset を足し、versionName の頭を versionMajor にする（gradle.properties）
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
val versionOffset = (project.findProperty("versionOffset") as String?)?.toIntOrNull() ?: 0
val versionMajor = (project.findProperty("versionMajor") as String?)?.toIntOrNull() ?: 1
val appVersionName = "$versionMajor.0.$buildNumber"

// GitHub Actions がリリースのタグ（v＋versionName）を作るのに使う
tasks.register("printVersionName") { doLast { println(appVersionName) } }

android {
    namespace = "com.example.weatherwidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.weatherwidget"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber + versionOffset
        // GitHub のリリースのタグは「v」＋ versionName（アプリの更新はタグで新しい版を見分ける）
        versionName = appVersionName
    }

    // 更新で上書きするには毎回同じ鍵で署名する。v1.25 まで使っていた Mac の debug 鍵（変えない）。
    // 鍵は公開リポジトリに置かず、Actions が Secret（DEBUG_KEYSTORE_B64）から app/debug.keystore を作る
    signingConfigs {
        create("release") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // 使っていない部品を削って小さくする
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // 本物の気象庁などにつなぐテストは、GitHub Actions では外す（相手の都合で失敗してリリースが止まらないように）
        unitTests.all { if (System.getenv("CI") != null) it.exclude("**/*LiveTest*") }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    testImplementation("junit:junit:4.13.2")
    // Android の org.json は単体テストでは中身がないので、本物を入れる
    testImplementation("org.json:json:20240303")
}
