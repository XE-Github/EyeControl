import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// release 签名属性从 keystore.properties 读(该文件已 .gitignore,不进版本库/公开包）。
// 缺文件时 signingConfig 保持 null → release 出未签名包而非构建失败，便于无密钥环境编译。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// 匿名统计上报令牌:从 local.properties 的 analyticsToken 读(该文件已 gitignore,不进版本库)。
// 缺失=空串 → App 灰度模式(只写 logcat 不上报)。令牌属专用采集小号、仅统计私库写权限,
// 泄露爆炸半径隔离在小号,绝不进源码/git。
val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localPropsFile.exists()) localPropsFile.inputStream().use { load(it) }
}
val analyticsToken: String = (localProps.getProperty("analyticsToken") ?: "").trim()

android {
    namespace = "com.eyecontrol.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eyecontrol.app"
        minSdk = 26              // dispatchGesture 需 API24+；26 覆盖绝大多数在用机型且省心
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        // 令牌注入 BuildConfig.ANALYTICS_TOKEN(空串=灰度不上报)。
        buildConfigField("String", "ANALYTICS_TOKEN", "\"$analyticsToken\"")
    }

    signingConfigs {
        // 仅当 keystore.properties 存在时才建 release 签名配置（否则无密钥环境不报错、出未签名包）。
        if (keystorePropsFile.exists()) {
            create("release") {
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
            // keystore.properties 在则自动签名，否则 null（未签名包）。你永不用手输密码。
            signingConfig = signingConfigs.findByName("release")
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
        viewBinding = true
        buildConfig = true   // 需要 BuildConfig.DEBUG 守卫调试指令(仅 debug 包生效)
    }
    // .task 模型若手动放进 assets，不要被压缩（自动下载方案下无所谓，留着无害）
    androidResources {
        noCompress += "task"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // MediaPipe 人脸关键点（与 Web Demo 同款算法/模型）
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // CameraX：前置摄像头帧分析
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")   // PreviewView：悬浮窗里渲染相机预览，保住"App 可见"→相机不被后台掐

    // 前台服务里承载 CameraX 生命周期 + lifecycleScope 协程
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
