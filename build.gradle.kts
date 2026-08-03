// 根工程：只声明插件版本，不 apply（子模块各自 apply）
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
