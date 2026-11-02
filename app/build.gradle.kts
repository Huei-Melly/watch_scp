plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.jay.watch_scp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jay.watch_scp"
        minSdk = 28
        // 手表 ROM 普遍没有「所有文件访问」设置页；targetSdk 29 配合
        // requestLegacyExternalStorage 可使用旧版存储模式，只靠运行时读权限即可浏览全部文件
        // （这也是同类第三方文件管理器能看全部文件的做法）
        targetSdk = 29
        versionCode = 1
        versionName = "1.8"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        // targetSdk 29 是为手表旧版存储模式（requestLegacyExternalStorage）刻意保留的，
        // 应用不通过 Google Play 分发，忽略该项检查，避免 lintVitalRelease 阻塞打包
        disable += "ExpiredTargetSdkVersion"
    }

    // release 打包产物命名为「软件名-版本号.apk」，debug 保持原名便于安装调试
    applicationVariants.all {
        if (buildType.name == "release") {
            outputs.all {
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                    "WatchSCP-${versionName}.apk"
            }
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.recyclerview)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.material)
    implementation(libs.jsch)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Wear OS dependencies
    implementation(libs.play.services.wearable)
    implementation(libs.wear)
    implementation(libs.documentfile)
}