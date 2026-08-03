package com.eyecontrol.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * 前台服务:后台常开摄像头做眨眼检测,命中"连眨 N 下"后经无障碍服务滑动翻页。
 *
 * 生命周期:MainActivity 点"开始" → startForegroundService(此服务)。
 * 它拉起 CameraX、加悬浮窗、把每帧喂给 FaceAnalyzer→BlinkDetector,并把 detector 的
 * 手势回调转给 SwipeAccessibilityService。
 */
class CameraService : LifecycleService(), BlinkDetector.Listener {

    companion object {
        private const val TAG = "CameraService"
        private const val CHANNEL = "eyecontrol_fg"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.eyecontrol.app.STOP"

        @Volatile var running = false
            private set

        /**
         * 【仅供 debug 注入用】指向当前运行中的 detector。debug 包的 DebugBlinkReceiver 拿它
         * 回放脚本化 EAR 序列做全自主测试。release 不引用(仅 src/debug 源集读取)。
         */
        @Volatile var liveDetector: BlinkDetector? = null
    }

    private lateinit var detector: BlinkDetector
    private var analyzer: FaceAnalyzer? = null
    private var overlay: OverlayView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    // ContextCompat.getMainExecutor 兼容 minSdk26(Context.getMainExecutor 需 API28)
    private val mainExec by lazy { ContextCompat.getMainExecutor(this) }

    override fun onCreate() {
        super.onCreate()
        detector = BlinkDetector(this).apply {
            nextN = Prefs.nextN(this@CameraService)
            holdMs = Prefs.holdMs(this@CameraService)
            bias = Prefs.biasOf(Prefs.sens(this@CameraService))
        }
        liveDetector = detector
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        startForegroundNotif()
        Log.i(TAG, "服务启动:前台通知已建")
        // 悬浮窗权限可能在服务重启时已被系统撤销,加保护避免 addView 抛异常导致崩溃。
        try {
            overlay = OverlayView(this).apply { show(detector.nextN) }
            Log.i(TAG, "悬浮窗已显示,previewView=${overlay?.previewView != null}")
        } catch (e: Exception) {
            Log.e(TAG, "悬浮窗创建失败(可能权限被撤销):${e.message}")
        }
        detector.reset()

        // 模型可能需首次下载,放后台线程;就绪后再拉起相机(在主线程)。
        lifecycleScope.launch {
            Log.i(TAG, "准备模型 …")
            val path = try {
                withContext(Dispatchers.IO) { ModelProvider.ensureModel(this@CameraService) }
            } catch (e: Exception) {
                Log.e(TAG, "模型准备失败:${e.message}")
                toast("模型加载失败,请检查网络后重试")
                stopSelf(); return@launch
            }
            Log.i(TAG, "模型就绪:$path,启动相机")
            startCamera(path)
        }
        running = true
        // 验证阶段用 NOT_STICKY:被系统杀掉后不自动重启(重启时可能权限已变),由用户重新点开始。
        return START_NOT_STICKY
    }

    private fun startCamera(modelPath: String) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                // 【任务#44 收尾】第 4 参 onBusyChanged:FaceAnalyzer 侦测到深坑(连续高 procMs)
                // 进/出时的翻转沿回调,单向转给悬浮窗显「卡顿…」提示。此信号只从 FaceAnalyzer 流出,
                // 绝不写回 detector(守 harness 绕过 FaceAnalyzer 仍能测判定路径的不变式)。
                // 回调在 analysisExecutor 单线程触发,overlay.setBusy 内部走 post{} 切主线程,安全。
                analyzer = FaceAnalyzer(this, modelPath, detector, onBusyChanged = { b -> overlay?.setBusy(b) })

                // 【性能·治抖音前台慢半拍】显式限低分析分辨率到 480×360(4:3)。
                // 默认不设分辨率时 CameraX 给 ~640×480+,每帧 toBitmap 全尺寸拷贝在抖音抢
                // 内存带宽/GPU + 48°C 降频时耗时爆炸(实测 analyzeMs 峰值 1144ms→fps 崩到个位数)。
                // 480×360 像素≈640×480 的 56%,MediaPipe 内部还会再缩放,landmark 对分辨率不敏感,
                // EAR 质量不退化。回退规则 CLOSEST_LOWER_THEN_HIGHER:前摄不支持精确档时取最近的
                // 更低档、否则更高档,绝不 bind 失败(切勿用 FALLBACK_RULE_NONE)。Preview 是独立
                // UseCase(1×1px 保活),分辨率与此解耦,不受影响。
                val resSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(480, 360),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resSelector)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, analyzer!!) }

                provider.unbindAll()

                // 关键:同时绑一路 Preview 到悬浮窗的 PreviewView。这块真实渲染的相机预览
                // 让系统认 App"可见",从而在用户刷抖音(我们的 Activity 在后台)时不掐断相机。
                val pv = overlay?.previewView
                if (pv != null) {
                    val preview = Preview.Builder().build()
                    pv.post { preview.setSurfaceProvider(pv.surfaceProvider) }
                    provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
                    )
                    Log.i(TAG, "相机已启动(Preview+Analysis 双路绑定)")
                } else {
                    // 悬浮窗没起来(权限被撤等)——退化为只绑分析,前台可用,后台可能被掐
                    provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis
                    )
                    Log.w(TAG, "相机已启动(仅 Analysis,无预览;后台可能被系统掐断)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "相机启动失败:${e.message}", e)
                toast("摄像头启动失败:${e.message}")
                stopSelf()
            }
        }, mainExec)
    }

    // ---- BlinkDetector.Listener ----
    // 诊断日志(验证阶段):把检测内幕打到 logcat,便于远程判断"数得准不准/方向对不对"。
    override fun onReady() { Log.i(TAG, "检测就绪(基线学好)"); overlay?.setReady(true) }
    override fun onEyeState(open: Boolean) { Log.i(TAG, if (open) "睁" else "闭"); overlay?.setEye(open) }
    override fun onCount(n: Int) { Log.i(TAG, "连眨计数=$n"); overlay?.setCount(n) }
    override fun onHoldProgress(fraction: Float) { overlay?.setHoldProgress(fraction) }
    override fun onRecalibrated() { Log.i(TAG, "环境突变已自动重校准") }

    override fun onNext() {
        Log.i(TAG, "★命中:下一个(连眨 ≥${detector.nextN} 下)→ 发滑动广播")
        overlay?.flash("⬆ 下一个")
        sendSwipe(next = true)
    }

    override fun onPrev() {
        Log.i(TAG, "★命中:上一个(闭眼保持 ${detector.holdMs}ms)→ 发滑动广播")
        overlay?.flash("⬇ 上一个")
        sendSwipe(next = false)
    }

    /**
     * 无障碍服务在独立进程(:a11y),静态 instance 跨进程失效,故用广播下发滑动指令。
     * 广播只在本 App 内部流转(setPackage 限定),不对外暴露。
     */
    private fun sendSwipe(next: Boolean) {
        val intent = Intent(SwipeAccessibilityService.ACTION_SWIPE)
            .setPackage(packageName)
            .putExtra(SwipeAccessibilityService.EXTRA_NEXT, next)
        sendBroadcast(intent)
    }

    // ---- 前台通知 ----
    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "眨眼控制运行中", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("眨眼控制运行中")
            .setContentText("连眨 ${detector.nextN} 下=下一个，闭眼保持=上一个")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .addAction(0, "停止", stopIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun toast(msg: String) {
        mainExec.execute { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        running = false
        liveDetector = null
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        analyzer?.close()
        overlay?.remove()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
