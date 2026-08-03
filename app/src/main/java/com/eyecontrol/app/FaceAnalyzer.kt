package com.eyecontrol.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * CameraX 帧分析器:每帧 → MediaPipe FaceLandmarker(LIVE_STREAM)→ 关键点 → 喂 BlinkDetector。
 *
 * FaceLandmarker LIVE_STREAM 是异步的:detectAsync() 返回后,结果通过 resultListener 回调,
 * 我们在回调里把归一化关键点拆进 FloatArray,交给 detector。
 */
class FaceAnalyzer(
    ctx: Context,
    modelPath: String,
    private val detector: BlinkDetector,
    // 【任务#44 收尾】深坑(连续高 procMs)进/出的翻转沿回调,只在状态翻转时调一次。
    // 默认空 lambda:别处若直接 new FaceAnalyzer(无第4参)不受影响。
    private val onBusyChanged: (Boolean) -> Unit = {},
) : ImageAnalysis.Analyzer {

    companion object {
        // 【性能·降级开关】true=旋转交 MediaPipe 内部处理(省一次全尺寸 Bitmap 拷贝);
        // false=走旧路径(toUprightBitmap 手动旋转 + 两参 detectAsync)。若某机型 GPU delegate
        // 下内部旋转出问题(EAR 异常/命中错),改回 false 一键回退,无需重构。稳定后再清理。
        private const val USE_MP_ROTATION = true
        // 【性能·自适应双档降帧】治任务#30 单档 66ms 引入的"眨3记2":真机高温下实际只~11-12fps,
        // 快眨谷仅 100~150ms 只被采到 1~2 帧,那帧恰落肩部(未到判定深度)就整下漏掉。改双档:
        //   · LOW(安静看视频):~15fps 省电降热——眨眼是稀疏事件,绝大多数时间在此档。
        //   · HIGH(眼睛一有动静):~30fps 保证快眨每下采够帧,不漏 ENTER。
        // 由 BlinkDetector.highRateUntilTs 驱动:EAR 一下沉/正在闭眼合内就把 latch 续 1s(> 连眨串
        // 间隔 STREAK_GAP_MS=900),整串连眨全程高帧,串后安静 >1s 才回落省电档。
        private const val LOW_INTERVAL_MS = 66L    // 安静:~15fps
        private const val HIGH_INTERVAL_MS = 33L   // 活跃:~30fps

        // 【治 faces 停滞 stall 的采样死锁,2026-08-03 双 agent 读 MediaPipe 图源码 + 18:15-18:16 逐窗铁证】
        // 根因:MediaPipe FaceLandmarker LIVE_STREAM 【无运动模型】(无 Kalman/光流),它把上一帧的
        // 人脸框原样当本帧 ROI,只在跟踪丢失时才重跑检测器。"脸没怎么动"这假设在 20-60fps 成立、在
        // ~11-12fps 崩溃:帧间 ~85ms 里正常头动/眨眼改变裁剪就把脸移出陈旧 ROI → presence 跌破门槛 →
        // 空 faces + 跟踪链断 → 下一帧重检测又落进同样陷阱 → 空脸持续爆发。铁证:fps 掉到 ~11 时
        // frames 仍 +188(回调在发,非丢帧),faces 冻结 Δ=0 满 17s;fps 回 ~20 立刻恢复流。
        //
        // 死锁的另一半在本代码:无脸→onResult 走 onNoFace→从不置 highRateUntilTs(那只在 onEar 里按
        // EAR 武装,而 onEar 需要脸)→ 停在 LOW(~11fps)→ 陈旧 ROI 持续失败 → 仍无脸。自锁。
        //
        // 修:丢脸时【由 FaceAnalyzer 自己】强制 HIGH 档跑重捕获——帧间位移变小,ROI 更可能仍覆盖脸,
        // 一两帧就能重新锁上。但【有界】:只在"最近确实见过脸"的窗口内提速(REACQUIRE_WINDOW_MS),
        // 用户真的离开(长时间无脸)后就回落 LOW,不空烧 30fps。破死锁又不牺牲省电。
        private const val REACQUIRE_WINDOW_MS = 4000L  // 最后一帧真脸后,重捕获提速最多持续这么久

        // 【任务#44 收尾:深坑期悬浮窗"卡顿…"提示】抖音全屏偶发系统级资源争抢时 procMs 从 ~50ms
        // 飙到 226~641ms(fps 崩到 1.5~3,持续 ~20s)。此时最极端窗口(fps<~3,帧间隔~600ms)一次
        // 快眨的谷(100~150ms)整个落在两帧之间→那几下【从未产生 onEar 事件】→采样物理漏帧,
        // BlinkDetector 侧的 B 路(组超时顺延)够不着。软件降采样已真机旁证【无效并摘除】(缩图本身
        // 吃 CPU,深坑期 CPU 也被抢,反而加重 analyzeMs)。故不再尝试压 fps 的物理手段,改为【如实
        // 告知用户】:检测到连续高 procMs(卡顿)时,悬浮窗小点变琥珀+显"卡顿…",退出深坑即恢复。
        // 让"良性偶漏"变成"用户可感知可配合"。此信号【只在 FaceAnalyzer 内自产 → 经回调告知
        // CameraService→OverlayView 单向流动,绝不写回 detector】(守 harness 绕过 FaceAnalyzer 仍能
        // 测判定路径的不变式)。
        //
        // 【2026-08-03 真机复测暴露的判据 bug 与修正】初版用"连续 BUSY_ARM_FRAMES(3) 帧单帧
        // procMs>200"进 busy——真机复测【从不触发】。根因:深坑的真实形态是【孤立尖刺帧】(单帧
        // procMs 812/422/385/306 夹在正常帧之间),【不是】连续 3 帧都慢;busyStreak 累到 1 就被下一
        // 健康帧清零,永远够不到 3;且 fps 1~4 时"等 3 帧"要 1~3s 太迟钝。改为【每 2s 诊断窗评估一次
        // 的双通道兜底判据】(用户选"两者或"):本窗 fps<BUSY_FPS 或 峰值procMs>=BUSY_PROC_MS 任一满足
        // 即进 busy;连续 BUSY_CLEAR_WINDOWS 个窗两通道都健康才退(迟滞防抖闪)。判定挪到诊断窗末尾
        // (与 fps/峰值procMs 同源、天然对齐),孤立尖刺不再被健康帧清零、低 fps 也能直接进。
        // 阈值取自复测铁证:深坑窗 fps 0.9~7.8 / 峰值procMs 306~812;健康窗 fps 11~19 / 峰值procMs
        // 20~94——6.0 与 300 都把两簇干净分开(比原 200 略提,避开偶发 176/243 轻尖刺边缘误报)。
        private const val BUSY_FPS = 6.0             // 本 2s 窗 fps 低于此算深坑(通道一)
        private const val BUSY_PROC_MS = 300L        // 本 2s 窗 峰值单帧 procMs 超此算深坑(通道二·尖刺)
        private const val BUSY_CLEAR_WINDOWS = 2     // 连续这么多个诊断窗两通道都健康才退 busy(迟滞)
    }

    private val TAG = "FaceAnalyzer"
    // FaceLandmarker.createFromOptions 需要 Context;先于 landmarker 初始化。
    private val ctxRef = ctx.applicationContext
    private var lastTs = 0L
    // 降帧节流:上一帧【被接收送检】的时刻(与送检时间戳 lastTs 独立)。间隔不够就丢弃。
    private var lastAcceptedTs = 0L
    // 帧/人脸计数,用于节流日志(验证阶段确认帧是否真的在流入)
    private var frameCount = 0
    private var faceCount = 0
    private var lastLogTs = 0L
    // 【诊断·抖音前台掉帧】瞬时帧率 + 单帧处理耗时:上面 frames/faces 是累计值,看不出
    // "抖音抢 CPU 后帧率掉到多少、一帧要多久"。这两个才是"反应迟钝/计数点慢半拍"的直接量。
    // winFrames=本 2s 窗内实际出帧数(→ fps=winFrames/2);inFlightTs=analyze 送检时刻,
    // 到 onResult 回调算出单帧 MediaPipe 处理耗时(procMs);analyzeMs=analyze() 自身(含
    // toUprightBitmap 拷贝+旋转)的耗时——若它大,瓶颈在预处理而非模型。
    private var winFrames = 0
    private var maxProcMs = 0L
    private var maxAnalyzeMs = 0L
    private var inFlightTs = 0L
    // 【任务#44 收尾 深坑侦测·窗口双通道判据】每 2s 诊断窗末评估:fps<BUSY_FPS 或 峰值procMs>=
    // BUSY_PROC_MS 任一满足即深坑;calmWindows 累计连续健康窗数,连够 BUSY_CLEAR_WINDOWS 才退 busy
    // (迟滞防抖闪)。均在 onResult(analysisExecutor 单线程)读写。此信号只经 onBusyChanged 回调外发,
    // 【绝不写回 detector】——否则 harness(绕过 FaceAnalyzer)就测不到判定路径。
    private var calmWindows = 0
    private var busy = false
    // 【重捕获提速】最后一帧【真有脸】的时刻,以及最近一帧是否丢脸。
    // 丢脸后据此判断是"刚丢、最近还见过脸(值得提速重捕获)"还是"早就没人了(该回落 LOW 省电)"。
    // 关键:提速只在【当前正丢脸】(faceMissing=true)且仍在重捕获窗内时生效——脸在时不提速,
    // 让平静看视频维持 LOW 省电,活动由 detector.highRateUntilTs(EAR 武装)负责。
    // @Volatile:analyze() 与 onResult() 都在 analysisExecutor 单线程跑,与 highRateUntilTs 一致约定。
    @Volatile private var lastFaceTs = 0L
    @Volatile private var faceMissing = false

    // 复用缓冲,避免每帧分配
    private var xs = FloatArray(478)
    private var ys = FloatArray(478)

    private var landmarker: FaceLandmarker = try {
        build(Delegate.GPU, modelPath)          // 优先 GPU;失败回退 CPU
    } catch (e: Exception) {
        Log.w(TAG, "GPU 初始化失败,回退 CPU:${e.message}")
        build(Delegate.CPU, modelPath)
    }

    private fun build(delegate: Delegate, modelPath: String): FaceLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath(modelPath)
            .setDelegate(delegate)
            .build()
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            // 【治真机"眨3停在计数1/2"的采样上游根因,2026-07-30 逐帧实证】
            // 真机数据:低帧率窗口(12-14fps,analyzeMs 仅 1-6ms 证明非预处理瓶颈、是抖音抢 GPU)
            // 里 MediaPipe 对【闭眼那几帧】连续 ~2s 返回 0 face(帧诊断 faces 停滞:frames +24 但
            // faces +0)→ onNoFace 清空连眨组 → 眨到 2 就丢。根因是闭眼这个动作本身把人脸
            // presence/tracking 置信度压到 0.5 门槛以下,把脸丢了。
            // presence/tracking 0.5→0.3:闭眼是脸的正常瞬态,不该被当"脸消失";放宽让闭眼期
            // 保住人脸轨迹连续,眨眼谷帧不再丢脸。detection(首次找脸)仍留 0.5,只影响"从无到
            // 有地找到脸"这一步,不影响已锁定后的跟踪,故不放松、避免误锁非人脸物体。
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.3f)
            .setMinTrackingConfidence(0.3f)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { e -> Log.e(TAG, "landmarker error: ${e.message}") }
            .build()
        return FaceLandmarker.createFromOptions(ctxRef, opts)
    }

    override fun analyze(image: ImageProxy) {
        val t0 = SystemClock.uptimeMillis()
        // ① 自适应双档降帧,HIGH(33ms/~30fps)/ LOW(66ms/~15fps),满足其一即用 HIGH:
        //    (a) detector.highRateUntilTs:眼睛有动静(EAR 下沉/inBlink)时提速,保证快眨每下采够不漏;
        //    (b) 重捕获窗口:【刚丢脸且最近确实见过脸】时提速——低 fps 下 MediaPipe 无运动模型的陈旧
        //        ROI 会持续失败(见 companion 注),提速把帧间位移压小让 ROI 仍覆盖脸、一两帧就重新锁上。
        //        破"无脸→停 LOW→陈旧ROI失败→仍无脸"的自锁。有界(REACQUIRE_WINDOW_MS):用户真离开后
        //        (长时间无脸)lastFaceTs 过期 → 回落 LOW 省电,不空烧 30fps。
        //    highRateUntilTs / lastFaceTs / t0 同为 uptimeMillis,可直接比。丢帧只 close 不 detectAsync,
        //    故 lastTs 仍单调、LIVE_STREAM 时间戳不破。放在 toBitmap 前,被丢帧连全尺寸拷贝都省了。
        val reacquiring = faceMissing && lastFaceTs > 0L && (t0 - lastFaceTs) < REACQUIRE_WINDOW_MS
        val interval = if (t0 < detector.highRateUntilTs || reacquiring) HIGH_INTERVAL_MS else LOW_INTERVAL_MS
        if (t0 - lastAcceptedTs < interval) { image.close(); return }
        lastAcceptedTs = t0

        // ② 预处理:USE_MP_ROTATION=true 时只做一次 toBitmap()(不旋转),把旋转角交给
        //    MediaPipe 内部处理,省掉第二次全尺寸 Bitmap.createBitmap 旋转拷贝(analyzeMs 大头)。
        val deg = image.imageInfo.rotationDegrees
        val bitmap: Bitmap? = if (USE_MP_ROTATION) {
            try { image.toBitmap() } catch (_: Exception) { null }
        } else {
            image.toUprightBitmap()     // 旧路径:手动旋转摆正
        }
        image.close()
        if (bitmap == null) return

        // LIVE_STREAM 要求时间戳单调递增
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTs) ts = lastTs + 1
        lastTs = ts

        val mp = BitmapImageBuilder(bitmap).build()
        inFlightTs = SystemClock.uptimeMillis()                 // 送检时刻,onResult 里算模型耗时
        val am = inFlightTs - t0                                // analyze() 自身耗时(主要是 bitmap 预处理)
        if (am > maxAnalyzeMs) maxAnalyzeMs = am
        try {
            if (USE_MP_ROTATION) {
                // 旋转角交 MediaPipe:它内部按 deg 摆正后再检测,返回的归一化 landmark 是"正立后"
                // 坐标,与旧手动旋转等价 → BlinkDetector 的眼睛索引/EAR 几何完全不变。
                val ipo = ImageProcessingOptions.builder().setRotationDegrees(deg).build()
                landmarker.detectAsync(mp, ipo, ts)
            } else {
                landmarker.detectAsync(mp, ts)
            }
        } catch (e: Exception) {
            Log.e(TAG, "detectAsync 失败:${e.message}")
        }
    }

    private fun onResult(result: FaceLandmarkerResult) {
        val now = SystemClock.uptimeMillis()
        val faces = result.faceLandmarks()
        frameCount++
        winFrames++
        val procMs = if (inFlightTs > 0) now - inFlightTs else 0L   // 送检→出结果 = MediaPipe 单帧耗时
        if (procMs > maxProcMs) maxProcMs = procMs
        if (faces.isEmpty()) { faceMissing = true; detector.onNoFace(now) }
        else {
            faceCount++
            faceMissing = false
            lastFaceTs = now   // 记下最后一帧真脸:丢脸后据此判断是否仍在重捕获窗内(该提速)
            val lm = faces[0]
            val n = lm.size
            if (xs.size < n) { xs = FloatArray(n); ys = FloatArray(n) }
            for (i in 0 until n) { xs[i] = lm[i].x(); ys[i] = lm[i].y() }
            detector.onFrame(xs, ys, now)
        }
        // 每约 2 秒打一次:瞬时帧率 + 峰值单帧耗时。fps 低 = 慢半拍的直接证据;
        // procMs 大 = 模型跑不动(GPU 被抢),analyzeMs 大 = 预处理(bitmap 拷贝旋转)是瓶颈。
        if (now - lastLogTs > 2000) {
            val fps = winFrames * 1000.0 / (now - lastLogTs).coerceAtLeast(1)
            Log.i(TAG, "帧诊断 fps=${"%.1f".format(fps)} 峰值procMs=$maxProcMs 峰值analyzeMs=$maxAnalyzeMs " +
                "(累计 frames=$frameCount faces=$faceCount)")
            // 【深坑侦测·窗口双通道兜底】本窗 fps 过低 或 峰值单帧 procMs 过高,任一满足即深坑。
            // 进 busy 立即翻(不等迟滞,深坑要尽快提示);退 busy 要连 BUSY_CLEAR_WINDOWS 个窗都健康
            // (迟滞防抖闪)。只在【翻转沿】调 onBusyChanged 一次。判定挪到窗末与 fps/峰值procMs 同源。
            // 守卫 winFrames>=2:本窗至少收过两帧才拿 fps 判深坑——排除相机刚起(frames=1)/纯空窗的
            // 启动瞬态低 fps 误报(那不是资源争抢,只是还没起流)。尖刺通道靠真实 procMs,无此顾虑。
            val windowBusy = (winFrames >= 2 && fps < BUSY_FPS) || maxProcMs >= BUSY_PROC_MS
            if (windowBusy) {
                calmWindows = 0
                if (!busy) { busy = true; onBusyChanged(true) }
            } else {
                calmWindows++
                if (busy && calmWindows >= BUSY_CLEAR_WINDOWS) { busy = false; onBusyChanged(false) }
            }
            lastLogTs = now; winFrames = 0; maxProcMs = 0; maxAnalyzeMs = 0
        }
    }

    fun close() { try { landmarker.close() } catch (_: Exception) {} }
}

/**
 * ImageProxy → 正立 Bitmap。用 CameraX 自带的 toBitmap()(1.3.0+)避免手写 stride 拷贝的坑;
 * toBitmap() 不含旋转,前置摄像头带旋转角时再按 rotationDegrees 摆正。
 */
private fun ImageProxy.toUprightBitmap(): Bitmap? {
    val bmp = try { toBitmap() } catch (_: Exception) { return null }
    val deg = imageInfo.rotationDegrees
    if (deg == 0) return bmp
    val m = Matrix().apply { postRotate(deg.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}
