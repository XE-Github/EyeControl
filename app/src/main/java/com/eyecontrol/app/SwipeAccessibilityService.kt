package com.eyecontrol.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务:用 dispatchGesture 模拟竖直滑动,在任意 App(抖音/快手…)里翻视频。
 *
 * 这是全案跨 App 控制的唯一合规途径。用户需在系统设置里手动开启本服务。
 *
 * 【独立进程 :a11y】本服务运行在独立轻量进程,与相机/MediaPipe 重活隔离,
 * 保证 onServiceConnected 永远秒回,绝不会因主进程卡顿被系统判超时→"服务出现故障"。
 * 代价:静态 instance 引用跨进程失效,故 CameraService 改用【广播】发滑动指令,
 * 本服务在此进程内注册接收器执行 dispatchGesture。
 *
 * 【健壮性】onServiceConnected / 接收器 / 手势全程 try-catch 兜底,
 * 任一环抛异常都不会让服务进程崩溃(那会再次触发"服务出现故障")。
 */
class SwipeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SwipeA11y"

        /** CameraService 命中手势后发这个广播;本服务在 :a11y 进程接收并滑动。 */
        const val ACTION_SWIPE = "com.eyecontrol.app.SWIPE"
        /** true=下一个(下→上滑);false=上一个(上→下滑)。 */
        const val EXTRA_NEXT = "next"

        /** 本服务【回传】滑动结果给主进程(诊断用):a11y 独立进程,主进程静态读不到 dispatch 成败。 */
        const val ACTION_SWIPE_STAT = "com.eyecontrol.app.SWIPE_STAT"
        const val EXTRA_OK = "ok"     // 累计 dispatchGesture 返回 true 的次数
        const val EXTRA_FAIL = "fail" // 累计返回 false / 被取消 的次数
    }

    // dispatchGesture 成败累计(仅本 :a11y 进程内计,经广播回传主进程缓存供诊断)。
    private var swipeOk = 0
    private var swipeFail = 0

    // 收到 CameraService 的滑动广播 → 执行滑动。注册在 :a11y 进程,拿得到本服务实例。
    private val swipeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            try {
                val next = intent?.getBooleanExtra(EXTRA_NEXT, true) ?: true
                Log.i(TAG, "收到滑动广播:next=$next → dispatchGesture")
                verticalSwipe(fromBottom = next)
            } catch (e: Exception) {
                Log.e(TAG, "处理滑动广播出错:${e.message}")
            }
        }
    }
    private var receiverRegistered = false

    override fun onServiceConnected() {
        // 这里绝不能做任何可能阻塞/抛异常的重活——只注册一个轻量广播接收器。
        super.onServiceConnected()
        try {
            val filter = IntentFilter(ACTION_SWIPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 应用内部广播,不导出;RECEIVER_NOT_EXPORTED 满足 API33+ 强制要求
                registerReceiver(swipeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(swipeReceiver, filter)
            }
            receiverRegistered = true
            Log.i(TAG, "无障碍已连接(:a11y 进程,广播接收器就绪)")
        } catch (e: Exception) {
            // 即便注册失败也不让服务崩;记录即可,用户可重开。
            Log.e(TAG, "注册广播接收器失败:${e.message}")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterSafely()
        Log.i(TAG, "无障碍已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        unregisterSafely()
        super.onDestroy()
    }

    private fun unregisterSafely() {
        if (receiverRegistered) {
            try { unregisterReceiver(swipeReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 不需要监听事件 */ }
    override fun onInterrupt() {}

    private fun verticalSwipe(fromBottom: Boolean) {
        try {
            val dm = resources.displayMetrics
            val w = dm.widthPixels.toFloat()
            val h = dm.heightPixels.toFloat()
            val x = w * 0.5f
            // 竖直方向留出上下边距,避免命中状态栏/手势条
            val yLow = h * 0.75f
            val yHigh = h * 0.28f

            val path = Path()
            if (fromBottom) { path.moveTo(x, yLow); path.lineTo(x, yHigh) }   // 上一屏→下一视频
            else            { path.moveTo(x, yHigh); path.lineTo(x, yLow) }   // 下一屏→上一视频

            val stroke = GestureDescription.StrokeDescription(path, 0, 220)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCancelled(g: GestureDescription?) {
                    Log.w(TAG, "手势被取消")
                    swipeFail++; sendSwipeStat()   // 异步取消也算一次失败,回传更新
                }
            }, null)
            if (ok) swipeOk++ else { swipeFail++; Log.w(TAG, "dispatchGesture 返回 false(可能未开启无障碍手势能力)") }
            sendSwipeStat()
        } catch (e: Exception) {
            Log.e(TAG, "verticalSwipe 出错:${e.message}")
        }
    }

    /** 把最新滑动累计回传主进程(内部广播,setPackage 限定;失败静默,绝不影响滑动)。 */
    private fun sendSwipeStat() {
        try {
            sendBroadcast(
                Intent(ACTION_SWIPE_STAT)
                    .setPackage(packageName)
                    .putExtra(EXTRA_OK, swipeOk)
                    .putExtra(EXTRA_FAIL, swipeFail)
            )
        } catch (_: Exception) {}
    }
}
