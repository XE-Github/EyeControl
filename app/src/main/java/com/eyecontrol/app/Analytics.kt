package com.eyecontrol.app

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 匿名活跃统计上报(旁路,绝不阻塞眨眼翻页主功能)。
 *
 * 目标:让作者知道有多少设备在用(日活 / 周活 / 版本 / 机型分布),判断这东西对普通大众值不值得继续做。
 *
 * ── 硬约束(隐私红线,不可违反)─────────────────────────────
 *  - 摄像头画面【永远只在本机处理】,埋点【绝不上报任何图像/人脸】。
 *  - 只上报匿名字段:随机安装标识 did(与真实身份无关)、App 版本 ver、机型 model、时间戳 ts。
 *  - 【同意门】:未同意 = 零采集(不生成 did、不上报)。见 Prefs.consent / ConsentDialog。
 *  - fire-and-forget:daemon 线程发出即忘(不读响应体),全程 try/catch 静默吞,失败丢弃。
 *  - 每设备每天只上报一次(Prefs.lastTrackDay 节流)——把写量压到 ≈ 日活条/天,极省。
 *
 * ── 上报通道:直连 Gitee 私有仓库(国内可达、免费、无写额度天花板)──────────
 *  Gitee Contents API「创建文件」:POST /repos/{owner}/{repo}/contents/{path}
 *    body: access_token + content(Base64) + message + branch。创建不需 sha。
 *  每设备每天写【独立文件】data/YYYY-MM-DD/<did>.ndjson —— 走创建路径,永不需 sha、
 *  永不并发冲突;文件存在本身即代表"该设备当天活跃"(聚合数目录文件数即得 DAU)。
 *
 * ── 令牌纪律(Gitee 无仓库级细粒度令牌,令牌是账号级的)─────────────
 *  - 令牌属于【专用采集小号】,该小号下只有这一个统计私库;泄露爆炸半径隔离在小号,
 *    绝不碰主账号任何代码库,更不碰 GitHub 上的签名 keystore。
 *  - 令牌【不写进源码/git】:编译期从 local.properties 注入到 BuildConfig(local.properties 不入库)。
 *    APK 里含令牌是已知取舍(泄露仅污染小号统计数据、可随时作废重发)。
 *  - 令牌为空 → 【灰度模式】:只写 logcat,不联网上报。填入后即接通,无需改别处。
 */
object Analytics {
    private const val TAG = "Analytics"

    // 采集小号 + 统计私库(小号下仅此一库)。owner/repo 是公开信息,可入库。
    // 小号 jaxinleon_1 与主账号 jaxinleon 隔离:令牌泄露只污染此统计库,碰不到主账号代码/keystore。
    private const val OWNER = "jaxinleon_1"
    private const val REPO = "eyecontrol-analytics"
    private const val BRANCH = "master"

    // 令牌从 BuildConfig 注入(见 app/build.gradle.kts 读 local.properties)。空 = 灰度不上报。
    private val TOKEN: String get() = BuildConfig.ANALYTICS_TOKEN

    private const val API_BASE = "https://gitee.com/api/v5/repos"
    private const val TIMEOUT_MS = 8_000

    // 上报计日用本地时区(与用户直觉一致;聚合侧按目录名统计,口径统一)。
    private val dayFmt: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }

    /**
     * App 打开:每设备每天最多上报一次(承载日活/周活/版本/机型)。
     * 应在 consent=="granted" 时调用;内部再判一次同意门,双保险。
     */
    fun trackAppOpen(ctx: Context) {
        if (Prefs.consent(ctx) != "granted") return       // 零采集
        val today = dayFmt.format(Date())
        if (Prefs.lastTrackDay(ctx) == today) return       // 今天已报,节流
        Prefs.setLastTrackDay(ctx, today)                  // 先记(即便发送失败也不再重试,严守每天一次)

        val did = Prefs.getOrCreateDid(ctx)
        val ver = try { BuildConfig.VERSION_NAME } catch (_: Exception) { "unknown" }
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".take(64)

        // 一行 NDJSON:该设备今天来过 + 版本/机型。文件路径已带 did/day,内容只承载维度。
        val line = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("ver", ver)
            put("model", model)
        }.toString()

        postCreateAsync("data/$today/$did.ndjson", line + "\n")
    }

    /**
     * 创建文件到 Gitee(daemon 线程,fire-and-forget)。
     * 令牌为空则灰度:只写 logcat 不联网。全程 try/catch 静默,绝不影响主功能。
     */
    private fun postCreateAsync(path: String, content: String) {
        val token = TOKEN
        if (token.isBlank()) {
            // 灰度模式:不上报,仅本地留痕便于验证埋点触发(不含任何隐私)。
            Log.i(TAG, "[灰度·未上报] would create $path : ${content.trim()}")
            return
        }
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val u = URL("$API_BASE/$OWNER/$REPO/contents/$path")
                if (u.protocol != "https") return@Thread     // 只走 https
                val b64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val body = JSONObject().apply {
                    put("access_token", token)
                    put("content", b64)
                    put("message", "track")
                    put("branch", BRANCH)
                }.toString()

                conn = (u.openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                    setRequestProperty("User-Agent", "EyeControl-Analytics")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode      // 触发发送;不读响应体(fire-and-forget)
                // 201=创建成功;400/409 多为"文件已存在"(同天重复,节流理应挡住,兜底也无妨)。
                Log.i(TAG, "track HTTP $code $path")
            } catch (e: Exception) {
                Log.w(TAG, "track 失败(已静默丢弃): ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }.apply { isDaemon = true }.start()
    }
}
