package com.eyecontrol.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * 【仅 debug 包】把脚本化的眨眼序列注入正在运行的 BlinkDetector,做"无需真人眨眼"的全自主闭环测试。
 *
 * 位于 src/debug/ 源集 + 只在 debug manifest 里声明 exported,【绝不进 release 包】。
 * 原理:CameraService 把当前 detector 暴露为 companion 的 liveDetector;本接收器拿到它,
 * 先关掉真实相机帧(detector.injecting=true 让 onFrame 空转),再按脚本调 detector.onEar(ear, now)
 * 回放一段合成 EAR 序列——走的是与真实帧【完全相同】的基线/状态机/计数/命中/悬浮窗回调路径。
 * 于是 logcat 里的 连眨计数 / 渲染亮点 / ★命中 / 发滑动广播 三层日志就能自动比对,无需人眼。
 *
 * 用法(pattern: fast3 | slow3 | hold | stray1 | split3 | mixed | sink | spike3):
 *   adb shell am broadcast -a com.eyecontrol.app.DEBUG_BLINK_SHELL --es pattern fast3 -p com.eyecontrol.app
 * 可选 --ei gap <ms> 覆盖连眨相邻间隔(默认按 pattern 取值)。
 */
class DebugBlinkReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "DebugBlink"
        const val OPEN = 0.30      // 睁眼基线附近的 EAR
        const val CLOSED = 0.08    // 闭眼谷底 EAR(明显低于判定线)
        const val FRAME_MS = 33L   // ~30fps
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pattern = intent.getStringExtra("pattern") ?: "fast3"
        val gapOverride = intent.getIntExtra("gap", -1)
        // 后台线程回放,避免阻塞广播主线程;真实相机帧在此期间被 injecting 门挡掉。
        Thread {
            try {
                val det = ensureDetector() ?: run {
                    Log.w(TAG, "【调试·注入】无法取得 liveDetector(服务未起且自动拉起失败)")
                    return@Thread
                }
                run(det, pattern, gapOverride)
            } catch (e: Exception) { Log.e(TAG, "注入异常:${e.message}") }
        }.start()
    }

    /**
     * 取运行中的 detector。服务未起时【不能】从这里拉起——HyperOS Greezer 会冻结冷进程、
     * 拒收广播(本接收器根本不会被调用)。正确的引导链是先 `am start ... --ez autostart true`
     * 让 MainActivity 前台化解冻进程 + 起前台服务钉住,之后本注入广播才能送达。
     * 这里只做短暂等待,兜住"服务刚起、liveDetector 尚未挂上"的竞态。
     */
    private fun ensureDetector(): BlinkDetector? {
        val deadline = SystemClock.uptimeMillis() + 15000L
        while (SystemClock.uptimeMillis() < deadline) {
            CameraService.liveDetector?.let { return it }
            Thread.sleep(200L)
        }
        return CameraService.liveDetector
    }

    private fun run(det: BlinkDetector, pattern: String, gapOverride: Int) {
        Log.i(TAG, "════ 注入开始 pattern=$pattern gapOverride=$gapOverride ════")
        det.injecting = true
        try {
            // 合成时钟:以当前 uptime 为起点,按帧真实推进(sleep),
            // 保证喂给 detector 的 now 与墙钟一致,cooldown/holdMs 等毫秒判定才有意义。
            clk = SystemClock.uptimeMillis()
            // 先 reset,丢弃真实相机遗留的 ready/基线,才能从零量"学习时间"、并保证回放干净。
            det.reset()

            // ① 预热基线:喂稳定睁眼帧,直到 detector 就绪(reset 后 ready=false;喂到就绪为止)。
            //    逐帧喂并计时,量出"从开喂到就绪"的学习时间(= 真机冷启动学习时间的合成等价)。
            val warmStart = clk
            var readyAt = -1L
            var fed = 0
            while (fed < 300) {                 // 上限 ~10s 兜底
                det.onEar(OPEN, clk); Thread.sleep(FRAME_MS); clk += FRAME_MS; fed++
                if (readyAt < 0 && det.isReady()) { readyAt = clk - warmStart
                    Log.i(TAG, "★学习时间(就绪) = ${readyAt}ms(喂了 $fed 帧)") }
                if (readyAt >= 0 && clk - warmStart >= readyAt + 800) break  // 就绪后再稳 0.8s
            }
            Log.i(TAG, "预热完毕,开始回放 $pattern")

            when (pattern) {
                "fast3"  -> blinks(det, 3, gapOr(gapOverride, 220), closedMs = 100)
                "slow3"  -> blinks(det, 3, gapOr(gapOverride, 500), closedMs = 130)
                "stray1" -> blinks(det, 1, gapOr(gapOverride, 0),   closedMs = 120)
                "hold"   -> hold(det, 750L)
                // 三次快眨,但每次谷底带一个"明显中途回升"(partial reopen 到 ~0.22 再闭回),
                // 复现真机上"一次眨眼被 rebounded 逻辑切成两下"→ 计数虚高/错位 → "眨3显2"的真凶。
                "split3" -> for (b in 0 until 3) { splitBlink(det); idleFrames(det, gapOr(gapOverride, 240)) }
                "mixed"  -> {
                    blinks(det, 3, gapOr(gapOverride, 240), closedMs = 100)
                    idle(det, 1200L)
                    hold(det, 750L)
                }
                // 【回归·睁眼水平下移不得误发"上一个"】睁眼水平随距离/角度/放松在数秒内【平滑滑下】,
                // 这【不是】闭眼手势——眼睛全程睁着,只是 EAR 沉到较低睁眼位。从 0.30 用 2s 线性滑到 0.21、
                // 再在 0.21 平台稳 4s。护住两条不变式:①baseline 靠 rawWin 高分位平滑追降(不死锁、0 RECAL);
                // ②即便相对 drop 一时虚高误入 inBlink,PREV_CLOSED_RATIO 绝对闭眼门也必须挡下这次假 PREV
                // (ear≈openLevel 达不到闭眼深度)。期望:全程【0 次 ★命中、0 次 RECAL】。
                "sink"   -> {
                    var e = clk; val start = clk
                    val rampMs = 2000L; val flatMs = 4000L; val hi = 0.30; val lo = 0.21
                    while (e - start < rampMs) {
                        val frac = (e - start).toDouble() / rampMs
                        det.onEar(hi + (lo - hi) * frac, e); Thread.sleep(FRAME_MS); e += FRAME_MS
                    }
                    val flatStart = e
                    while (e - flatStart < flatMs) { det.onEar(lo, e); Thread.sleep(FRAME_MS); e += FRAME_MS }
                    clk = e
                    Log.i(TAG, "  ▷ 已注入 2s 渐变(0.30→0.21)+4s 平台,验 baseline 平滑跟降、0 误发")
                }
                // 【回归·任务#44 抖音尖刺深坑"眨3停在1"】眨 2 下 → 模拟一帧被资源争抢吞掉 1800ms
                // (procMs 深坑:一帧就跨过整个 window=600ms)→ 眨第 3 下。关键:尖刺那帧【只跳 clk
                // 不 sleep】,喂给 detector 的 now 直接跳 1800ms,复现真机"两帧之间隔 1.8s"的稀疏采样。
                // 修前:第 3 下之前 groupDeadline 早已 now>=deadline 触发 finalizeGroup→n<3 清零(停在2)、
                //       无 ★命中。修后:那帧 gap=1800ms 触发 B 路 deadline 顺延,组不被冲掉,第 3 下拼进来
                //       达 nextN=3 → ★命中下一个。顺带覆盖 reset→预热首帧陈旧 lastFrameTs 特例(gap 哨兵)。
                "spike3" -> {
                    blinks(det, 2, gapOr(gapOverride, 220), closedMs = 100)   // 前两下正常快眨
                    // 尖刺:一帧吞 1800ms(不 sleep,直接跳时钟),这帧是睁眼、不产生眨眼
                    clk += 1800L
                    det.onEar(OPEN, clk)
                    Log.i(TAG, "  ▷ 已注入尖刺:一帧吞 1800ms(模拟 procMs 深坑,跨过 window)")
                    // 第 3 下(手写单次眨眼,起点距上一下 >130ms 天然过组内去重):下降→谷→上升
                    var e = clk + FRAME_MS
                    det.onEar(0.20, e); Thread.sleep(FRAME_MS); e += FRAME_MS
                    det.onEar(0.13, e); Thread.sleep(FRAME_MS); e += FRAME_MS
                    val vStart = e; var k = 0
                    while (e - vStart < 100) {
                        val v = if (k % 2 == 1) 0.11 else CLOSED
                        det.onEar(v, e); Thread.sleep(FRAME_MS); e += FRAME_MS; k++
                    }
                    det.onEar(0.15, e); Thread.sleep(FRAME_MS); e += FRAME_MS
                    det.onEar(0.22, e); Thread.sleep(FRAME_MS); e += FRAME_MS
                    clk = e
                    Log.i(TAG, "  ▷ 已注入第 3 次眨眼(应拼进被顺延保住的组、达 3 下命中)")
                }
                else -> Log.w(TAG, "未知 pattern=$pattern(可选 fast3/slow3/stray1/hold/split3/mixed/sink/spike3)")
            }
            // 收尾:再喂一小段睁眼帧,让"没够数的组"超时清零、flash 恢复等都跑完。
            idle(det, 1500L)
        } finally {
            // 关键:注入结束后立刻 reset(),丢弃合成基线。否则真实相机帧一恢复,
            // 会拿"真实脸的 EAR"去比"合成的 0.30 基线",瞬间算出大 drop → 误判闭眼 →
            // 550ms 后误发一次"上一个"(harness 自身实测到的假 PREV)。真实使用无此切换,
            // 但测试必须干净收尾,不把假信号算进结果。
            det.reset()
            det.injecting = false
            Log.i(TAG, "════ 注入结束 pattern=$pattern(已 reset,丢弃合成基线)════")
        }
    }

    // 合成时钟(ms),各回放段共享并单调推进。
    private var clk = 0L

    /**
     * 回放 count 次眨眼(带真实形状)。真实眨眼不是矩形凹陷,而是一个圆弧谷:
     * 下降(睁→闭)→ 谷底(可带抖动)→ 上升(闭→睁)。谷底抖动模拟真机上 EAR 在最低点
     * 反复微跳,正是它触发状态机的 rebounded 逻辑、把"一次眨眼切成两下"的根源
     * (用户反馈"快速眨三次只显示两次"的真凶)。
     * @param valleyJitter 谷底是否加抖动(模拟真实噪声);gapMs=相邻眨眼【起点】间隔。
     */
    private fun blinks(
        det: BlinkDetector, count: Int, gapMs: Int, closedMs: Int, valleyJitter: Boolean = true,
    ) {
        for (b in 0 until count) {
            var e = clk
            val cycleStart = e
            // 下降沿:2 帧从睁到闭
            det.onEar(0.20, e); Thread.sleep(FRAME_MS); e += FRAME_MS
            det.onEar(0.13, e); Thread.sleep(FRAME_MS); e += FRAME_MS
            // 谷底:闭眼 closedMs,可带抖动(0.08↔0.11 微跳,模拟真机噪声)
            val vStart = e
            var k = 0
            while (e - vStart < closedMs) {
                val v = if (valleyJitter && k % 2 == 1) 0.11 else CLOSED
                det.onEar(v, e); Thread.sleep(FRAME_MS); e += FRAME_MS; k++
            }
            // 上升沿:2 帧从闭到睁
            det.onEar(0.15, e); Thread.sleep(FRAME_MS); e += FRAME_MS
            det.onEar(0.22, e); Thread.sleep(FRAME_MS); e += FRAME_MS
            // 睁眼段:补足到 gapMs(下一次眨眼起点),最少留一帧睁眼产生"睁眼边沿"
            val elapsed = (e - cycleStart).toInt()
            val openSpan = maxOf(FRAME_MS.toInt(), gapMs - elapsed)
            var o = 0
            while (o < openSpan) {
                det.onEar(OPEN, e); Thread.sleep(FRAME_MS); e += FRAME_MS; o += FRAME_MS.toInt()
            }
            clk = e
            Log.i(TAG, "  ▷ 已注入第 ${b + 1} 次眨眼(谷${closedMs}ms抖动=$valleyJitter,间隔${gapMs}ms)")
        }
    }

    /**
     * 一次"带明显中途回升"的眨眼:下降→谷底→回升到 0.22(看似要睁开)→再闭回谷底→睁开。
     * 这正是 rebounded 逻辑(drop 回落到峰值 55% 以下 ≥40ms)会误判为"两次眨眼"的形状。
     * 用来复现"一次眨眼被切两下"→ 计数错位 → 用户"快眨三次只显示两次"。
     */
    private fun splitBlink(det: BlinkDetector) {
        var e = clk
        det.onEar(0.18, e); Thread.sleep(FRAME_MS); e += FRAME_MS   // 下降
        det.onEar(0.08, e); Thread.sleep(FRAME_MS); e += FRAME_MS   // 谷底1
        det.onEar(0.08, e); Thread.sleep(FRAME_MS); e += FRAME_MS
        det.onEar(0.22, e); Thread.sleep(FRAME_MS); e += FRAME_MS   // 中途回升(伪睁开)
        det.onEar(0.23, e); Thread.sleep(FRAME_MS); e += FRAME_MS
        det.onEar(0.09, e); Thread.sleep(FRAME_MS); e += FRAME_MS   // 再闭回谷底2
        det.onEar(0.08, e); Thread.sleep(FRAME_MS); e += FRAME_MS
        det.onEar(0.18, e); Thread.sleep(FRAME_MS); e += FRAME_MS   // 睁开
        clk = e
        Log.i(TAG, "  ▷ 已注入一次【中途回升】眨眼(应算作 1 下,谨防被切成 2 下)")
    }

    /** 喂 ms 睁眼帧(不改语义,仅时间推进);与 idle 等价但名字表意"眨眼间隔"。 */
    private fun idleFrames(det: BlinkDetector, ms: Int) = idle(det, ms.toLong())

    /** 闭眼保持 holdMs+余量,验证"上一个"。保持前先喂足睁眼稳定期(PREV_ARM_MS)。 */
    private fun hold(det: BlinkDetector, holdMs: Long) {
        // 先稳定睁眼 700ms(> PREV_ARM_MS=500),武装 PREV
        idle(det, 700L)
        val start = clk
        var e = clk
        val span = holdMs + 200L   // 越过 holdMs 一点确保命中
        while (e - start < span) { det.onEar(CLOSED, e); Thread.sleep(FRAME_MS); e += FRAME_MS }
        clk = e
        Log.i(TAG, "  ▷ 已注入闭眼保持 ${span}ms")
    }

    /** 喂一段睁眼帧(空闲/间隔)。 */
    private fun idle(det: BlinkDetector, ms: Long) {
        var e = clk
        val start = clk
        while (e - start < ms) { det.onEar(OPEN, e); Thread.sleep(FRAME_MS); e += FRAME_MS }
        clk = e
    }

    private fun gapOr(override: Int, def: Int): Int = if (override > 0) override else def
}
