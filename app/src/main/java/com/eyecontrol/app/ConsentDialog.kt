package com.eyecontrol.app

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 首次启动的匿名统计【同意门】。
 *
 * 产品承诺:未同意 = 零采集(连随机标识都不生成、绝不上传)。同意与否都不影响 App 任何功能。
 * 逐条把"采集什么 / 绝不采集什么"讲清楚,让普通用户能看懂地做知情选择——这是隐私红线的一部分。
 *
 * 只用系统 AlertDialog(与现有更新弹框一致,零新增依赖)。同意→granted 并触发一次 app_open;
 * 拒绝→declined(此后永不上报,也不再弹)。
 */
object ConsentDialog {

    /**
     * 若从未询问过(consent==""),弹同意门;否则直接回调 onDone(已是终态)。
     * @param onDone 用户做出选择(或本就有终态)后回调,参数为最终 consent 态。
     */
    fun ensure(ctx: Context, onDone: (granted: Boolean) -> Unit) {
        val cur = Prefs.consent(ctx)
        if (cur == "granted") { onDone(true); return }
        if (cur == "declined") { onDone(false); return }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("帮助改进(匿名)")
            .setMessage(
                "为了了解有多少人在用、值不值得继续做得更好,想匿名统计一点点信息:\n\n" +
                    "会收集:\n" +
                    "· 一个随机编号(与你的真实身份无关,用来粗略去重)\n" +
                    "· App 版本、手机型号\n" +
                    "· 打开时间\n\n" +
                    "绝不收集:\n" +
                    "· 任何摄像头画面 / 人脸(画面永远只在本机处理)\n" +
                    "· 你的姓名、账号、通讯录、位置等任何个人身份信息\n\n" +
                    "说明:每台设备每天最多上报一次;卸载重装后编号会变,所以统计只是粗略估算。\n" +
                    "无论你是否同意,App 的全部功能都照常使用。"
            )
            .setCancelable(false)
            .setPositiveButton("同意") { _, _ ->
                Prefs.setConsent(ctx, "granted")
                onDone(true)
            }
            .setNegativeButton("不了,谢谢") { _, _ ->
                Prefs.setConsent(ctx, "declined")
                onDone(false)
            }
            .show()
    }
}
