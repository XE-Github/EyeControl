package com.eyecontrol.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 【仅 debug 包】接收来自 adb shell 的调试指令,在 app 进程内转发成真实的内部滑动广播,
 * 用于全自主回归测试"命中→广播→:a11y→dispatchGesture→翻页"。
 *
 * 位于 src/debug/ 源集 + 只在 debug manifest 里声明 exported,因此【绝不会】进入 release 产品包。
 * shell(uid 2000)发的广播能被这个 exported receiver 收到;它随即在 app 内部(同 uid)发送
 * SwipeAccessibilityService 那条 RECEIVER_NOT_EXPORTED 广播——走的是与真实命中完全相同的路径。
 *
 * 用法:
 *   adb shell am broadcast -a com.eyecontrol.app.DEBUG_SWIPE_SHELL --ez next true  -p com.eyecontrol.app
 */
class DebugSwipeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val next = intent.getBooleanExtra("next", true)
        Log.i("CameraService", "【调试·shell】收到调试指令 next=$next → 转发内部滑动广播")
        val fwd = Intent(SwipeAccessibilityService.ACTION_SWIPE)
            .setPackage(context.packageName)
            .putExtra(SwipeAccessibilityService.EXTRA_NEXT, next)
        context.sendBroadcast(fwd)
    }
}
