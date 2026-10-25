plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * 构建时的提交短哈希：内置进 BuildConfig，供「检查更新」与分支仓库最新提交比对。
 * 不在 git 工作区（如源码压缩包）构建时为空串，此时只提示分支最新提交、不判定是否有更新。
 */
val gitCommitShort: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }
        .standardOutput.asText.get().trim()
}.getOrDefault("")

android {
    namespace = "com.controleco.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.controleco.app"
        minSdk = 26
        targetSdk = 35
        // 每次带修改的构建把第三位 +1；1.0.0 起为正式版本，版本名不再带 -beta 后缀
        versionCode = 40
        versionName = "1.0.5"
        // 内置构建提交短哈希，供「检查更新」判断本版本分支仓库是否有新提交
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommitShort\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 正式版仍沿用 debug 密钥签名：与既有侧载包同一签名，可直接覆盖安装、不清数据。
            // 换成独立发布密钥需要用户先卸载（账本会一起没），1.0.0 不做。
            signingConfig = signingConfigs.getByName("debug")
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
