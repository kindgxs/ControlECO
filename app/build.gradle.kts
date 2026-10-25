import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 独立签名材料（不入库）：存在时用新密钥签名，缺失时回落到 debug 签名
val keystorePropsFile = rootProject.file("signing/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.controleco.app"
    compileSdk = 35

    defaultConfig {
        // 与原版 com.controleco.app 不同的包名，系统才会允许两个应用并存安装
        applicationId = "com.controleco.app.merged"
        minSdk = 26
        targetSdk = 35
        // 每次带修改的构建把第三位 +1；1.0.0 起为正式版本，版本名不再带 -beta 后缀
        versionCode = 38
        versionName = "1.0.3-merged"
    }

    signingConfigs {
        create("mergedRelease") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 合并版用独立包名 + 独立密钥，可与原版同时安装；缺少签名材料时回落 debug 密钥
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("mergedRelease")
            } else {
                signingConfigs.getByName("debug")
            }
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
        // 单元测试中 android.util.Log 等框架方法返回默认值而非抛异常
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    // 导出 Room schema，用于校验迁移 SQL（app/schemas/）
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
