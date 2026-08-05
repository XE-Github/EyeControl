package com.eyecontrol.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 下载更新包 APK 并拉起系统安装界面。
 *
 * - 下载到 externalCacheDir(便于通过 FileProvider 授权给系统安装器,且不占用户可见存储、随缓存清理)。
 * - 沿用 ModelProvider 的下载写法:.part 临时文件 + rename,connect 15s / read 30s。
 * - 安装走 FileProvider content:// URI + FLAG_GRANT_READ_URI_PERMISSION(Android 7+ 不允许 file:// 传给别的应用)。
 * - 回调都在子线程触发,UI 层负责切主线程。
 */
object UpdateDownloader {
    private const val TAG = "UpdateDownloader"
    private const val APK_NAME = "EyeControl-update.apk"

    /**
     * 后台下载 APK。
     * @param onProgress 0..100,-1 表示总大小未知(仍会在完成时回调 onDone)。
     * @param onDone 下载成功,回传已就绪的 apk 文件。
     * @param onError 失败(网络/HTTP/写盘),回传原因文案。
     */
    fun download(
        ctx: Context,
        url: String,
        onProgress: (Int) -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        Thread {
            val dir = ctx.externalCacheDir ?: ctx.cacheDir
            val dst = File(dir, APK_NAME)
            val tmp = File(dir, "$APK_NAME.part")
            try {
                if (dst.exists()) dst.delete()
                if (tmp.exists()) tmp.delete()

                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                    instanceFollowRedirects = true            // GitHub/Gitee 下载直链会 302 到 CDN
                    setRequestProperty("User-Agent", "EyeControl-Updater")
                }
                try {
                    conn.connect()
                    if (conn.responseCode !in 200..299)
                        throw IllegalStateException("下载 HTTP ${conn.responseCode}")

                    val total = conn.contentLength.toLong()   // 可能为 -1(未知)
                    var read = 0L
                    var lastPct = -1
                    conn.inputStream.use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                read += n
                                if (total > 0) {
                                    val pct = (read * 100 / total).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                                }
                            }
                        }
                    }
                    if (total <= 0) onProgress(-1)
                } finally {
                    conn.disconnect()
                }

                if (tmp.length() < 1_000_000)                 // APK 约 49MB;<1MB 必是坏包/错页
                    throw IllegalStateException("下载文件异常(过小:${tmp.length()} 字节)")
                if (!tmp.renameTo(dst)) { tmp.copyTo(dst, overwrite = true); tmp.delete() }

                Log.i(TAG, "下载完成:${dst.length()} bytes → ${dst.absolutePath}")
                onDone(dst)
            } catch (e: Exception) {
                Log.w(TAG, "下载失败: ${e.message}")
                tmp.delete()
                onError(e.message ?: "下载失败")
            }
        }.apply { isDaemon = true }.start()
    }

    /** 用 FileProvider 拉起系统安装界面安装 apk。需在主线程调用。 */
    fun installApk(ctx: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)           // 从非 Activity 上下文启动也安全
        }
        ctx.startActivity(intent)
    }

    /**
     * Android 8+ 需要「允许安装未知来源应用」授权。返回 true 表示已可安装;
     * false 时 UI 层应引导用户去授权页(系统在安装那一刻通常也会自行引导,这里作为主动检查)。
     */
    fun canInstall(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.packageManager.canRequestPackageInstalls()
        else true
    }
}
