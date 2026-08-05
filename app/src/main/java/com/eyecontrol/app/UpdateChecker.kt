package com.eyecontrol.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用内检查更新(GitHub + Gitee 双源)。
 *
 * 设计要点:
 *  - 两源【同时并发】请求各自的 releases/latest,谁先成功用谁(国内常连不上 GitHub → Gitee 兜底;
 *    反之 Gitee 偶发限流/不可达 → GitHub 兜底)。两个都失败才回调 null。
 *  - 纯逻辑、无 Android UI 依赖;网络在子线程,回调在【调用线程之外的子线程】触发——
 *    UI 层(MainActivity)负责把回调 post 回主线程。
 *  - 只访问两个【公开】Release API,不携带任何令牌;不涉及任何图像/隐私数据。
 *  - 版本判定用逐段数值比较(1.10 > 1.9),不用字符串比较。
 *
 * 发版约定(务必遵守,否则检查更新失效):
 *  - Release 的 tag 命名 = "v" + versionName,如 v1.1 / v2.0,逐次递增。
 *  - APK 附件名以 .apk 结尾(如 EyeControl-v1.1.apk);Gitee 附件里会混入源码 zip,按 .apk 筛。
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"

    // 两个公开 Release API(latest)。免令牌可读。
    private const val GITHUB_API = "https://api.github.com/repos/XE-Github/EyeControl/releases/latest"
    private const val GITEE_API = "https://gitee.com/api/v5/repos/jaxinleon/EyeControl/releases/latest"

    private const val TIMEOUT_MS = 12_000

    /** 检查结果。downloadUrl 指向 .apk 附件的直链;source 用于日志/展示("Gitee"/"GitHub")。 */
    data class UpdateInfo(
        val versionName: String,   // 去掉了前缀 v 的版本串,如 "1.1"
        val tag: String,           // 原始 tag,如 "v1.1"
        val downloadUrl: String,
        val source: String,
        val notes: String,
    )

    /**
     * 并发检查两源,取【先返回的成功结果】回调一次;两源都失败回调 null。
     * 回调在子线程触发,UI 层需自行切回主线程。
     *
     * @param localVer 本地版本名(默认 BuildConfig.VERSION_NAME);仅用于日志,判定在 UI 层用 isNewer。
     */
    fun checkNow(callback: (UpdateInfo?) -> Unit) {
        val delivered = AtomicBoolean(false)      // 保证 callback 只触发一次
        val remaining = AtomicInteger(2)          // 两源都失败(计数归零)才回调 null

        fun attempt(name: String, api: String, tweak: (HttpURLConnection) -> Unit = {}) {
            Thread {
                val info = try {
                    fetch(name, api, tweak)
                } catch (e: Exception) {
                    Log.w(TAG, "$name 检查失败: ${e.message}")
                    null
                }
                if (info != null) {
                    // 先到者胜:第一个成功的独占回调
                    if (delivered.compareAndSet(false, true)) {
                        Log.i(TAG, "采用 $name 源:${info.tag} @ ${info.downloadUrl}")
                        callback(info)
                    }
                } else {
                    // 本源失败;若两源都失败且尚无人回调,则回调 null
                    if (remaining.decrementAndGet() == 0 && delivered.compareAndSet(false, true)) {
                        Log.w(TAG, "两源均失败,无法检查更新")
                        callback(null)
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        attempt("GitHub", GITHUB_API) { it.setRequestProperty("Accept", "application/vnd.github+json") }
        attempt("Gitee", GITEE_API)
    }

    /** 拉取并解析单个源;失败抛异常,成功返回 UpdateInfo(找不到 .apk 附件也算失败)。 */
    private fun fetch(source: String, api: String, tweak: (HttpURLConnection) -> Unit): UpdateInfo {
        val conn = (URL(api).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "EyeControl-Updater")
            tweak(this)
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299)
                throw IllegalStateException("HTTP ${conn.responseCode}")
            // 显式 UTF-8,不依赖 JVM 默认字符集(两源 API 均返回 UTF-8 JSON)
            val body = conn.inputStream.reader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)

            val tag = json.optString("tag_name").trim()
            if (tag.isEmpty()) throw IllegalStateException("无 tag_name")

            val apkUrl = firstApkUrl(json.optJSONArray("assets"))
                ?: throw IllegalStateException("Release 无 .apk 附件")

            val notes = sanitizeNotes(json.optString("body"))
            return UpdateInfo(
                versionName = stripV(tag),
                tag = tag,
                downloadUrl = apkUrl,
                source = source,
                notes = notes,
            )
        } finally {
            conn.disconnect()
        }
    }

    /** 从 assets 数组里取第一个 .apk 结尾的 browser_download_url(GitHub/Gitee 字段名一致)。 */
    private fun firstApkUrl(assets: JSONArray?): String? {
        if (assets == null) return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val url = a.optString("browser_download_url")
            if (url.endsWith(".apk", ignoreCase = true)) return url
        }
        return null
    }

    /**
     * 远端 tag(如 "v1.1")是否比本地版本(如 "1.0")更新。逐段数值比较,缺段补 0。
     * 例:isNewer("v1.10","1.9")=true(10>9)、isNewer("v1.0","1.0")=false。
     * 解析失败(非数字段)保守返回 false,避免误弹更新。
     */
    fun isNewer(remoteTag: String, localVer: String): Boolean {
        return try {
            val r = stripV(remoteTag).split(".")
            val l = stripV(localVer).split(".")
            val n = maxOf(r.size, l.size)
            for (i in 0 until n) {
                val rv = r.getOrNull(i)?.toIntOrNull() ?: 0
                val lv = l.getOrNull(i)?.toIntOrNull() ?: 0
                if (rv != lv) return rv > lv
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /** 去掉版本串前缀的 v/V,并裁掉首尾空白。 */
    private fun stripV(s: String): String =
        s.trim().removePrefix("v").removePrefix("V").trim()

    /**
     * 净化更新说明:剔除 U+FFFD 替换字符(上游 release body 若曾被错误编码写入,会含大量 �)。
     * 若净化后有效可读字符太少(基本是乱码),则返回空串——UI 侧空串不展示"更新内容"段,
     * 避免把一堆 � 塞给用户。这是对上游坏数据的兜底,不影响正常 UTF-8 说明。
     */
    private fun sanitizeNotes(raw: String): String {
        val cleaned = raw.replace("�", "").trim()
        // 剔除替换字符后,若剩下的非空白字符不足原文一半(说明原本大部分是乱码),视为无有效说明
        val rawNonSpace = raw.count { !it.isWhitespace() }
        val cleanNonSpace = cleaned.count { !it.isWhitespace() }
        if (rawNonSpace > 0 && cleanNonSpace < rawNonSpace / 2) return ""
        return cleaned
    }
}
