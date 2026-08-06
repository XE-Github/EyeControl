package com.eyecontrol.app

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 用户主动反馈 —— 本地不丢队列 + 有网自动补发 + 云端回执编号。
 *
 * ── 与埋点(Analytics)的本质区别:语义相反 ──────────────────────────
 *  Analytics = at-most-once(先记戳→fire-and-forget→失败即弃),日活埋点丢一条无所谓。
 *  Feedback  = at-least-once(先落本地队列文件→尝试上传→失败留队→下次补发直到成功)。
 *  【绝不允许"上报失败"这个用户可见结局】——那是投降式设计、算 bug。用户永远看不到"失败":
 *  发送成功给编号,发送失败则如实说"已保存,联网后自动发送",反馈已进队列、保证补发。
 *  只复用 Analytics 的 Gitee Contents API 写通道,不复用它的节流+丢弃语义。
 *
 * ── 诚实最高原则(用户拍板「201 才给编号」)────────────────────────
 *  「显示编号 ⟺ 云端此刻确有此文件」。fid 只在 Gitee 创建文件返回 201(或"文件已存在"=幂等
 *  等价送达)后才算送达、才显示编号。离线/超时【绝不伪造编号、绝不谎报失败】。
 *
 * ── 隐私 / 令牌红线 ────────────────────────────────────────
 *  反馈标识 fbid 独立于埋点 did(见 Prefs.getOrCreateFeedbackId),未同意匿名统计也能反馈。
 *  诊断快照只含标量/计数/EAR 比值,绝无图像/人脸(见 Diagnostics)。
 *  令牌与埋点同库同令牌(BuildConfig.ANALYTICS_TOKEN,空=灰度),绝不进源码/git;只走 https。
 */
object Feedback {
    private const val TAG = "Feedback"

    // 与埋点同库同令牌(采集小号私库),反馈落 feedback/ 目录。
    private const val OWNER = "jaxinleon_1"
    private const val REPO = "eyecontrol-analytics"
    private const val BRANCH = "master"
    private const val API_BASE = "https://gitee.com/api/v5/repos"
    private const val TIMEOUT_MS = 8_000
    private const val QUEUE_DIR = "feedback_queue"

    // postCreate 返回码约定:除 HTTP 码外的两个哨兵。
    private const val CODE_GRAYSCALE = -1     // 灰度(令牌空):没上报,不删队列、不伪造 201
    private const val CODE_ERROR = -2         // 网络/异常:没送达,留队列待补发

    private val TOKEN: String get() = BuildConfig.ANALYTICS_TOKEN

    private val dayFmt: SimpleDateFormat
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }

    /** 即时结果:回主线程决定 UI 相位。绝无"失败"态。 */
    sealed class Result {
        /** 云端已确认(201 或幂等已存在)→ 显示编号。 */
        data class Delivered(val fid: String) : Result()
        /** 已存队列、尚未确认送达(离线/超时/灰度)→ 显示"已保存,联网后自动发送",不给编号。 */
        data class Queued(val fid: String) : Result()
    }

    /**
     * 入队并尝试一次上传。
     *  1) 算 fid、组 body、【先原子写入队列文件】(保证不丢,即使随后进程被杀)。
     *  2) daemon 线程尝试上传一次;201/已存在 → 删队列文件 + 记送达表 → 回 Delivered;
     *     否则(灰度/离线/异常)→ 队列文件保留 → 回 Queued。
     * onImmediate 在主线程回调即时结果。全程 try/catch,绝不抛给调用方。
     */
    fun enqueueAndTry(ctx: Context, text: String, includeDiag: Boolean, onImmediate: (Result) -> Unit) {
        val appCtx = ctx.applicationContext
        val ts = System.currentTimeMillis()
        val fbid = Prefs.getOrCreateFeedbackId(appCtx)     // 仅此刻懒生成(用户点发送=知情同意)
        val fid = makeFid(fbid, ts)

        val body = buildBody(appCtx, fid, ts, text, includeDiag)
        // 先落盘(不丢):写失败也不阻断——退化为"尽力上传一次"。
        val queued = writeQueueFile(appCtx, fid, body)

        Thread {
            val code = postCreate(feedbackPath(ts, fid), body)
            val delivered = code == 201 || isAlreadyExists(code)
            if (delivered) {
                if (queued) deleteQueueFile(appCtx, fid)
                Prefs.markFeedbackDelivered(appCtx, fid, ts)
            }
            val result = if (delivered) Result.Delivered(fid) else Result.Queued(fid)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onImmediate(result) }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 扫本地队列,逐条尝试补发(供 MainActivity.onResume / CameraService 定时调用)。
     * 每条:读文件内容(即完整 POST body)→ POST → 201/已存在 → 删文件 + 记送达表。
     * 全程 daemon + try/catch 静默,绝不阻塞主线程、绝不抛异常。灰度(令牌空)时直接跳过(留队列)。
     */
    fun flushQueue(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (TOKEN.isBlank()) return                        // 灰度:不联网,留队列等令牌接通
        val dir = queueDir(appCtx)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (files.isEmpty()) return
        Thread {
            for (f in files) {
                try {
                    val body = f.readText(Charsets.UTF_8)
                    if (body.isBlank()) { f.delete(); continue }
                    val fid = f.nameWithoutExtension
                    val ts = extractTs(body)
                    val code = postCreate(feedbackPath(ts, fid), body)
                    if (code == 201 || isAlreadyExists(code)) {
                        f.delete()
                        Prefs.markFeedbackDelivered(appCtx, fid, ts)
                        Log.i(TAG, "补发送达 $fid (HTTP $code)")
                    } // 其它码:留文件,下轮再试
                } catch (e: Exception) {
                    Log.w(TAG, "补发一条出错(留队列待下次): ${e.message}")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 待发(队列里)条数,供"我的反馈"列表提示。 */
    fun pendingCount(ctx: Context): Int =
        (queueDir(ctx.applicationContext).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()).size

    /** 队列里所有待发 fid(最新在前),供"我的反馈"列表。 */
    fun pendingFids(ctx: Context): List<String> =
        (queueDir(ctx.applicationContext).listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { it.nameWithoutExtension }

    // ---- 内部实现 ----

    /**
     * 创建文件到 Gitee(【读 responseCode】)。复用埋点的 Contents API 写法,但语义相反:
     * 反馈要知道成没成(读码决定删不删队列),不是 fire-and-forget。
     * 返回:HTTP 状态码,或 CODE_GRAYSCALE(令牌空)/ CODE_ERROR(异常)。灰度绝不伪造 201。
     */
    private fun postCreate(path: String, body: String): Int {
        val token = TOKEN
        if (token.isBlank()) {
            Log.i(TAG, "[灰度·未上报] would create $path")
            return CODE_GRAYSCALE
        }
        var conn: HttpURLConnection? = null
        try {
            val u = URL("$API_BASE/$OWNER/$REPO/contents/$path")
            if (u.protocol != "https") return CODE_ERROR   // 只走 https
            // body 里是纯反馈 JSON;Gitee create 需要把它 Base64 后放进 content 字段的外层信封。
            val b64 = Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val envelope = JSONObject().apply {
                put("access_token", token)
                put("content", b64)
                put("message", "feedback")
                put("branch", BRANCH)
            }.toString()

            conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                setRequestProperty("User-Agent", "EyeControl-Feedback")
            }
            conn.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            Log.i(TAG, "feedback HTTP $code $path")
            return code
        } catch (e: Exception) {
            Log.w(TAG, "feedback 上传异常(留队列待补发): ${e.message}")
            return CODE_ERROR
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 幂等判定:同一 fid 第二次创建 Gitee 会返回"文件已存在"。Gitee 对此多返回 400,
     * 也可能 409/422。把这些当作"云端已有此文件"= 送达成功(与 201 等价),补发天然不重复。
     * (真正的鉴权/网络错误是 401/403/5xx/CODE_ERROR,不会被误判为送达。)
     */
    private fun isAlreadyExists(code: Int): Boolean = code == 400 || code == 409 || code == 422

    /** feedback/YYYY-MM-DD/<fid>.json(day 由反馈时刻的本地时区算,与埋点口径一致)。 */
    private fun feedbackPath(ts: Long, fid: String): String =
        "feedback/${dayFmt.format(Date(ts))}/$fid.json"

    /**
     * fid = ts36 + "-" + hash6。
     *  ts36 = 反馈毫秒的 Base36(天然递增、可读、短);
     *  hash6 = sha256(fbid + ":" + ts) 前 6 位 hex(fbid 保证不同设备/不同时刻不撞)。
     * 入队时定死,作为队列文件名与 Gitee 文件名 → 重发同一 fid 天然幂等。
     */
    private fun makeFid(fbid: String, ts: Long): String {
        val ts36 = ts.toString(36)
        val hash6 = try {
            val d = MessageDigest.getInstance("SHA-256").digest("$fbid:$ts".toByteArray(Charsets.UTF_8))
            d.joinToString("") { "%02x".format(it) }.take(6)
        } catch (_: Exception) {
            // 退化(几乎不会):用 fbid 尾部凑 6 位,仍唯一到设备+时刻。
            (fbid.filter { it.isLetterOrDigit() } + "000000").takeLast(6)
        }
        return "$ts36-$hash6"
    }

    /** 队列文件/上传内容 = 反馈 JSON 本身。 */
    private fun buildBody(ctx: Context, fid: String, ts: Long, text: String, includeDiag: Boolean): String {
        val ver = try { BuildConfig.VERSION_NAME } catch (_: Exception) { "unknown" }
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".take(64)
        return JSONObject().apply {
            put("fid", fid)
            put("ts", ts)
            put("ver", ver)
            put("model", model)
            put("text", text)
            put("diag", if (includeDiag) Diagnostics.snapshot(ctx) else JSONObject.NULL)
        }.toString()
    }

    /** 从队列文件内容里取回 ts(用于组 feedbackPath 的 day);解析失败退化为当前时刻。 */
    private fun extractTs(body: String): Long =
        try { JSONObject(body).optLong("ts", System.currentTimeMillis()) }
        catch (_: Exception) { System.currentTimeMillis() }

    private fun queueDir(ctx: Context): File =
        File(ctx.filesDir, QUEUE_DIR).apply { if (!exists()) mkdirs() }

    /** 原子写:.part 写完 renameTo 目标,避免半截文件(复用 UpdateDownloader/ModelProvider 的写法)。 */
    private fun writeQueueFile(ctx: Context, fid: String, body: String): Boolean = try {
        val dir = queueDir(ctx)
        val target = File(dir, "$fid.json")
        val part = File(dir, "$fid.json.part")
        part.writeText(body, Charsets.UTF_8)
        if (target.exists()) target.delete()
        val ok = part.renameTo(target)
        if (!ok) { part.delete() }
        ok
    } catch (e: Exception) {
        Log.w(TAG, "写队列文件失败(退化为尽力上传): ${e.message}")
        false
    }

    private fun deleteQueueFile(ctx: Context, fid: String) {
        try { File(queueDir(ctx), "$fid.json").delete() } catch (_: Exception) {}
    }
}
