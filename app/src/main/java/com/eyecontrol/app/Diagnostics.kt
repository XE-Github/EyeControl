package com.eyecontrol.app

import android.content.Context
import android.os.Build
import org.json.JSONObject

/**
 * 结构化诊断快照 —— 供"用户主动反馈"可选附带。
 *
 * ── 硬约束(隐私红线,不可违反)────────────────────────────────
 *  - 【只含标量 / EAR 比值 / 计数】,绝不含任何图像、人脸、关键点原始坐标。
 *  - 全部来自各组件既有的【只读 getter / 纯观测计数器】(BlinkDetector/FaceAnalyzer/CameraService/
 *    MainActivity 的 a11y 态),读取不改变任何检测行为。
 *  - 服务未运行时(liveDetector/liveAnalyzer 为 null),运行态字段【如实填 null】、detRunning=false,
 *    绝不伪造——诚实优先。
 *
 * 目的:让作者拿到能定位"眨了不翻 / 太敏感 / 某 ROM 失灵"的运行态,而无需任何画面。
 */
object Diagnostics {

    /** double 保留 n 位小数(避免 JSON 里出现浮点长尾;NaN/Inf 归 null 由调用方处理)。 */
    private fun round(v: Double, n: Int = 3): Double =
        try { "%.${n}f".format(v).toDouble() } catch (_: Exception) { v }

    /** 有限数才放进 JSON,NaN/Inf 一律 put null(诚实标注"无有效值")。 */
    private fun JSONObject.putFinite(key: String, v: Double, n: Int = 3) {
        if (v.isNaN() || v.isInfinite()) put(key, JSONObject.NULL)
        else put(key, round(v, n))
    }

    /**
     * 组一份诊断快照。检测服务在跑 → 运行态字段有值;没跑 → 那些字段为 null。
     * 静态字段(版本/机型/设置/同意态/a11y 连接态)任何时候都能取。
     */
    fun snapshot(ctx: Context): JSONObject {
        val o = JSONObject()
        val det = CameraService.liveDetector
        val ana = CameraService.liveAnalyzer
        val running = CameraService.running && det != null

        // ── 静态 / 环境态(任何时候可取)──
        o.put("detRunning", running)
        o.put("a11y", MainActivity.a11yStateOf(ctx))                 // NOT_ENABLED / RECONNECTING / READY
        o.put("nextN", Prefs.nextN(ctx))
        o.put("holdMs", Prefs.holdMs(ctx))
        o.put("sens", Prefs.sens(ctx))
        o.put("consent", Prefs.consent(ctx).ifEmpty { "unasked" })
        o.put("ver", try { BuildConfig.VERSION_NAME } catch (_: Exception) { "unknown" })
        o.put("model", "${Build.MANUFACTURER} ${Build.MODEL}".take(64))
        o.put("androidSdk", Build.VERSION.SDK_INT)

        // ── 检测器运行态(只读 getter;没跑填 null)──
        if (det != null) {
            o.put("ready", det.diagReady())
            o.putFinite("baseline", det.diagBaseline())
            o.putFinite("openLevel", det.diagOpenLevel())
            o.putFinite("noiseUp", det.diagNoiseUp())
            o.putFinite("peakDrop", det.diagPeakDrop())
            o.putFinite("dynEnter", det.diagDynEnter())
            o.putFinite("dynExit", det.diagDynExit())
            o.put("maxStreak", det.diagMaxStreak())
        } else {
            for (k in listOf("ready", "baseline", "openLevel", "noiseUp", "peakDrop", "dynEnter", "dynExit", "maxStreak"))
                o.put(k, JSONObject.NULL)
        }

        // ── 分析器运行态(帧率/耗时/命中;没跑填 null)──
        if (ana != null) {
            o.put("delegate", ana.diagDelegate())
            o.putFinite("fps", ana.diagFps(), 1)
            o.put("maxProcMs", ana.diagPeakProcMs())
            o.put("maxAnalyzeMs", ana.diagPeakAnalyzeMs())
            val frames = ana.diagFrames(); val faces = ana.diagFaces()
            o.put("frames", frames)
            o.put("faces", faces)
            o.putFinite("faceMissRatio", if (frames > 0) (frames - faces).toDouble() / frames else 0.0)
            o.put("everBusy", ana.diagEverBusy())
        } else {
            for (k in listOf("delegate", "fps", "maxProcMs", "maxAnalyzeMs", "frames", "faces", "faceMissRatio", "everBusy"))
                o.put(k, JSONObject.NULL)
        }

        // ── 本会话命中(CameraService 累计;没跑填 null——它们随会话清零,跨会话无意义)──
        if (running) {
            o.put("nextHits", CameraService.nextHits)
            o.put("prevHits", CameraService.prevHits)
            o.put("recalibrations", CameraService.recalibrations)
        } else {
            for (k in listOf("nextHits", "prevHits", "recalibrations")) o.put(k, JSONObject.NULL)
        }

        // ── 跨进程滑动结果(-1=从未收到 a11y 回传;转成 null 更直观)──
        o.put("a11ySwipeOk", if (CameraService.a11ySwipeOk < 0) JSONObject.NULL else CameraService.a11ySwipeOk)
        o.put("a11ySwipeFail", if (CameraService.a11ySwipeFail < 0) JSONObject.NULL else CameraService.a11ySwipeFail)

        return o
    }
}
