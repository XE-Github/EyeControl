package com.eyecontrol.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.view.PreviewView

/**
 * 悬浮窗(精简版):状态点 + 连眨计数点。可拖动。让用户一眼知道"有没有触发"即可。
 * 摄像头预览仍在(缩到 1×1px 近乎不可见),用户看不到画面,但相机保活不受影响。
 * 用 WindowManager + TYPE_APPLICATION_OVERLAY(需 SYSTEM_ALERT_WINDOW 权限)。
 *
 * 关键:窗口带 FLAG_HARDWARE_ACCELERATED,否则内嵌的 PreviewView(SurfaceView/TextureView)
 * 无法渲染;而正是这块真实渲染的相机预览让系统认 App"可见",从而在刷抖音时不掐相机。
 *
 * 窗口固定尺寸:触发/睁闭/计数变化只改文字与点颜色,不改布局,浏览时窗口不跳动。
 */
class OverlayView(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private lateinit var root: View
    private lateinit var stateDot: View
    private lateinit var stateText: TextView
    private lateinit var dots: LinearLayout
    private var added = false
    private var maxDots = 4
    // 记录当前语义状态,供 flash 命中反馈结束后恢复正确的"睁/闭/就绪"显示。
    private var ready = false
    private var lastEyeOpen = true
    // 【任务#44 收尾】深坑背景态:抖音抢资源致连续高 procMs 时置 true,悬浮窗显「卡顿…」+琥珀点,
    // 让用户知道"现在卡、良性偶漏"。它是【背景态】——睁/闭/命中 flash 照常发生,退出深坑即摘。
    // 由 CameraService 的 onBusyChanged 回调经 setBusy 驱动(源头是 FaceAnalyzer 的 procMs 侦测)。
    private var busy = false

    /** 相机预览控件,供 CameraService 绑定 Preview 用例。show() 后非空。 */
    var previewView: PreviewView? = null
        private set

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 24; y = 160
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show(maxCountDots: Int) {
        if (added) return
        maxDots = maxCountDots
        root = LayoutInflater.from(ctx).inflate(R.layout.overlay, null)
        previewView = root.findViewById<PreviewView>(R.id.preview).apply {
            // 悬浮窗里 SurfaceView(默认 PERFORMANCE)易有层级/渲染问题,用 TextureView 模式更稳
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        stateDot = root.findViewById(R.id.stateDot)
        stateText = root.findViewById(R.id.stateText)
        dots = root.findViewById(R.id.dots)
        buildDots(0)
        enableDrag()
        wm.addView(root, params)
        added = true
    }

    fun remove() {
        if (!added) return
        try { wm.removeView(root) } catch (_: Exception) {}
        previewView = null
        added = false
    }

    // 注:setReady/setEye 均先更新内部字段,再让最终显示交给"就绪→学习中/睁/闭 与 busy"的合流。
    // busy(深坑背景态)优先级最高:深坑期收到睁/闭/就绪事件不得冲掉「卡顿…」,退出深坑才现真态。
    fun setReady(isReady: Boolean) = post {
        ready = isReady
        if (busy) return@post           // 深坑期保「卡顿…」;退坑时 setBusy(false)→restoreState 会现真态
        stateText.text = if (isReady) "就绪" else "学习中"
        stateDot.setBackgroundResource(if (isReady) R.drawable.dot_green else R.drawable.dot_amber)
    }

    fun setEye(open: Boolean) = post {
        lastEyeOpen = open
        if (busy) return@post           // 同上:深坑期不用睁/闭冲掉「卡顿…」
        stateText.text = if (open) "睁" else "闭"
    }

    fun setCount(n: Int) = post { buildDots(n) }

    /**
     * 闭眼保持进度回调:精简版悬浮窗已移除进度条(避免窗口尺寸跳动),此处不做 UI。
     * 保留空实现,使 CameraService 的 Listener 接线无需改动。
     */
    @Suppress("UNUSED_PARAMETER")
    fun setHoldProgress(fraction: Float) { /* no-op：精简窗不显示进度条 */ }

    /**
     * 命中反馈:状态点变蓝 + 文字闪一下方向 + 计数点全亮(确认这一下达成),~700ms 后自动恢复。
     * 全亮很关键:即时触发("眨够 3 下当即下滑")时,第 3 下点亮与清零几乎同帧,
     * 若不在此刻把点补满,用户会误以为"只数到 2 下"。这里统一让点满亮再随恢复清零,
     * 使"眨的次数"与"看到的亮点数"始终一致。只改文字与点颜色,不改布局,窗口尺寸恒定。
     */
    fun flash(text: String) = post {
        stateText.text = text
        stateDot.setBackgroundResource(R.drawable.dot_blue)
        buildDots(maxDots)          // 命中瞬间把计数点补满,确认达成的下数
        root.postDelayed({
            if (added) {
                restoreState()      // 恢复背景态:仍在深坑则回「卡顿…」,否则回睁/闭
                buildDots(0)        // 反馈结束再清零,而非命中同帧清零(否则第 3 点看不见)
            }
        }, 700L)
    }

    /**
     * 【任务#44 收尾】深坑提示:抖音抢资源致连续高 procMs 时,悬浮窗小点变琥珀 + 文字显「卡顿…」,
     * 退出深坑恢复睁/闭常态。文案中性简短(不做"慢点眨"之类行为暗示),与"睁/闭/就绪"同风格。
     * 只改 stateText 文字与 stateDot 颜色,【不改窗口布局/尺寸、不加控件、不动 dots】(守窗口不跳动)。
     * busy 是【背景态】,与命中 flash(蓝、700ms 瞬时)不打架:flash 期间照常显示方向,
     * 其 postDelayed 尾走 restoreState() 会自然回到"仍 busy 则卡顿、否则睁/闭"的正确背景态。
     */
    fun setBusy(isBusy: Boolean) = post {
        busy = isBusy
        restoreState()
    }

    /**
     * 恢复背景态显示(供 flash 尾与 setBusy 共用,避免重复):
     * 仍在深坑(busy)→ 显「卡顿…」+ 琥珀点;否则 → 显睁/闭 + 就绪色点(绿=就绪/琥珀=学习中)。
     */
    private fun restoreState() {
        if (busy) {
            stateText.text = "卡顿…"
            stateDot.setBackgroundResource(R.drawable.dot_amber)
        } else {
            stateText.text = if (lastEyeOpen) "睁" else "闭"
            stateDot.setBackgroundResource(if (ready) R.drawable.dot_green else R.drawable.dot_amber)
        }
    }

    private fun buildDots(lit: Int) {
        // 诊断日志(验证阶段):把"悬浮窗实际点亮几个点"打出来,便于用监视器对齐
        // 检测端的"连眨计数=N"与前端真正渲染的亮点数,确认二者一致(排查"眨3下只亮2点")。
        android.util.Log.i("OverlayView", "渲染亮点=$lit / 共$maxDots")
        dots.removeAllViews()
        val size = (10 * ctx.resources.displayMetrics.density).toInt()
        val gap = (4 * ctx.resources.displayMetrics.density).toInt()
        for (i in 0 until maxDots) {
            val v = View(ctx)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginStart = if (i == 0) 0 else gap
            v.layoutParams = lp
            v.setBackgroundResource(if (i < lit) R.drawable.dot_blue else R.drawable.dot_gray)
            dots.addView(v)
        }
    }

    private var downX = 0; private var downY = 0
    private var touchX = 0f; private var touchY = 0f
    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag() {
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = params.x; downY = params.y
                    touchX = e.rawX; touchY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = downX + (e.rawX - touchX).toInt()
                    params.y = downY + (e.rawY - touchY).toInt()
                    if (added) wm.updateViewLayout(root, params); true
                }
                else -> false
            }
        }
    }

    private fun post(block: () -> Unit) {
        if (added) root.post(block)
    }
}
