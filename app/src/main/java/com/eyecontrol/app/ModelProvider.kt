package com.eyecontrol.app

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 提供 face_landmarker.task 模型文件的本地路径。
 *
 * 优先级:
 *   1) app 私有目录已存在 → 直接用(下载过的缓存)。
 *   2) assets 里打包了(离线方案)→ 拷到私有目录再用。
 *   3) 都没有 → 从 Google 官方地址下载到私有目录(首次联网一次,之后缓存)。
 *
 * 与 Web Demo 同款模型,保证算法一致。
 */
object ModelProvider {
    private const val TAG = "ModelProvider"
    const val FILE_NAME = "face_landmarker.task"
    private const val URL =
        "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"

    /** 返回可用的模型文件绝对路径;必要时同步下载(请在后台线程调用)。抛异常表示彻底失败。 */
    fun ensureModel(ctx: Context): String {
        val dst = File(ctx.filesDir, FILE_NAME)
        if (dst.exists() && dst.length() > 100_000) return dst.absolutePath

        // 尝试从 assets 拷贝(若用户选择离线打包)
        try {
            ctx.assets.open(FILE_NAME).use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "模型来自 assets,已拷到 ${dst.absolutePath}")
            return dst.absolutePath
        } catch (_: Exception) {
            // assets 里没有,走下载
        }

        Log.i(TAG, "assets 无模型,开始下载 …")
        download(dst)
        if (!dst.exists() || dst.length() < 100_000)
            throw IllegalStateException("模型下载失败(文件过小或不存在)")
        Log.i(TAG, "模型下载完成:${dst.length()} bytes")
        return dst.absolutePath
    }

    /** 是否已就绪(无需联网即可开始) */
    fun isReady(ctx: Context): Boolean {
        val f = File(ctx.filesDir, FILE_NAME)
        if (f.exists() && f.length() > 100_000) return true
        return try { ctx.assets.open(FILE_NAME).use { }; true } catch (_: Exception) { false }
    }

    private fun download(dst: File) {
        val tmp = File(dst.parentFile, "$FILE_NAME.part")
        val conn = (URL(URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            requestMethod = "GET"
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299)
                throw IllegalStateException("下载 HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }
        } finally {
            conn.disconnect()
        }
    }
}
