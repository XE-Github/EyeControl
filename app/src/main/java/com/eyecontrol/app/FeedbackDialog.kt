package com.eyecontrol.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用户主动反馈对话框 —— 纯代码构建(零布局文件,与 ConsentDialog 一致)。
 *
 * ── 相位(严守用户拍板「201 才给编号」+「不允许失败」)────────────────────
 *  input     文本必填(空则禁用发送)+「附带诊断信息」CheckBox(默认勾)。
 *  sending   点发送 → 按钮禁用显「提交中…」,后台 enqueueAndTry(先落盘不丢 + 尝试上传一次)。
 *  delivered 云端 201/幂等已存在 → 显反馈编号 fid + 「复制编号」。
 *  queued    离线/超时/灰度 → 显「已保存 ✓ …联网后自动发送…」,【无编号、无"失败"字样】。
 *  任何相位都绝不出现"失败"——反馈已进本地队列,保证补发(见 Feedback 的 at-least-once)。
 *
 * ── 我的反馈 ──────────────────────────────────────────────
 *  input 相位一个「我的反馈」入口,弹列表:已送达(显编号,可复制)+ 待发(离线发的,联网后补发)。
 *  这是「201 才给编号 + 离线发」闭环的必要一环:离线发的反馈补发成功后,用户回来能查到编号。
 */
object FeedbackDialog {

    private const val PAD = 20   // dp,视觉留白

    fun show(activity: Activity) {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        // ── 输入区 ──
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(PAD), px(PAD - 4), px(PAD), 0)
        }

        val edit = EditText(activity).apply {
            hint = "说说遇到的问题或建议(选填,也可只附诊断信息直接发)"
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
        }
        root.addView(edit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val diagCheck = CheckBox(activity).apply {
            text = "附带诊断信息(版本 / 机型 / 检测运行状态,绝不含任何画面或人脸)"
            isChecked = true
            textSize = 13f
        }
        root.addView(diagCheck, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(8) })

        val privacyHint = TextView(activity).apply {
            text = "🔒 只上传你写的文字和(可选)运行状态,绝不上传任何摄像头画面。离线也能发,联网后会自动送达。"
            textSize = 12f
            setPadding(0, px(8), 0, 0)
        }
        root.addView(privacyHint)

        val myBtn = TextView(activity).apply {
            text = "查看「我的反馈」›"
            textSize = 13f
            setPadding(0, px(12), 0, 0)
            setOnClickListener { showMyFeedback(activity) }
        }
        root.addView(myBtn)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("反馈 / 报告问题")
            .setView(wrapScroll(activity, root))
            .setPositiveButton("发送", null)   // 手动接管,防空文本时自动关闭
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            val sendBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            // 文本【非必填】:发送按钮始终可用,空文本也能发(可只附诊断)。用户拍板不设必填门槛。
            sendBtn.setOnClickListener {
                val text = edit.text.toString().trim()
                // → sending 相位:禁用两个按钮 + 文案,后台上传,不关本框(改成结果框)。
                sendBtn.isEnabled = false
                sendBtn.text = "提交中…"
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                Feedback.enqueueAndTry(activity, text, diagCheck.isChecked) { result ->
                    if (activity.isFinishing || activity.isDestroyed) return@enqueueAndTry
                    dialog.dismiss()
                    when (result) {
                        is Feedback.Result.Delivered -> showDelivered(activity, result.fid)
                        is Feedback.Result.Queued -> showQueued(activity)
                    }
                }
            }
        }
        dialog.show()
    }

    /** delivered:云端已确认,显编号 + 复制。 */
    private fun showDelivered(activity: Activity, fid: String) {
        AlertDialog.Builder(activity)
            .setTitle("已收到,感谢反馈!")
            .setMessage("反馈编号:\n$fid\n\n凭此编号可向作者追问处理进度。")
            .setPositiveButton("复制编号") { _, _ -> copy(activity, fid, "反馈编号已复制") }
            .setNegativeButton("好的", null)
            .show()
    }

    /** queued:未确认送达(离线/超时/灰度)。【无编号、无"失败"字样】——已进队列,保证补发。 */
    private fun showQueued(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("已保存 ✓")
            .setMessage("反馈已保存到本机。当前可能没有网络,联网后会自动发送;成功后可在「我的反馈」里看到编号。")
            .setPositiveButton("好的", null)
            .show()
    }

    /**
     * 「我的反馈」列表:已送达(送达表,显编号可复制)+ 待发(队列目录里的 fid,离线发的)。
     * 只读本机记录,不发网络(补发由前台钩子在别处触发)。
     */
    private fun showMyFeedback(activity: Activity) {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val dayFmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

        val delivered = Prefs.deliveredFeedback(activity)     // List<fid to ts>,最新在前
        val pending = Feedback.pendingFids(activity)          // List<fid>,最新在前

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(PAD), px(8), px(PAD), 0)
        }

        if (delivered.isEmpty() && pending.isEmpty()) {
            box.addView(TextView(activity).apply {
                text = "还没有反馈记录。遇到问题随时来提,离线也能发哦。"
                textSize = 14f
            })
        } else {
            if (pending.isNotEmpty()) {
                box.addView(sectionLabel(activity, px(0)) { "待发送(${pending.size})· 联网后自动发送" })
                for (fid in pending) {
                    box.addView(itemRow(activity, title = "⏳ 待发送", sub = fid, copyable = false))
                }
            }
            if (delivered.isNotEmpty()) {
                box.addView(sectionLabel(activity, px(if (pending.isEmpty()) 0 else 14)) { "已送达(${delivered.size})" })
                for ((fid, ts) in delivered) {
                    val when0 = try { dayFmt.format(Date(ts)) } catch (_: Exception) { "" }
                    box.addView(itemRow(activity, title = "✅ $when0", sub = fid, copyable = true))
                }
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("我的反馈")
            .setView(wrapScroll(activity, box))
            .setPositiveButton("关闭", null)
            .show()
    }

    // ---- 小部件助手 ----

    private fun wrapScroll(ctx: Context, child: View): ScrollView =
        ScrollView(ctx).apply { addView(child) }

    private fun sectionLabel(ctx: Context, topPx: Int, textOf: () -> String): TextView =
        TextView(ctx).apply {
            text = textOf()
            textSize = 12f
            setPadding(0, topPx, 0, (ctx.resources.displayMetrics.density * 4).toInt())
        }

    /** 一条记录:标题 + fid(可选点按复制)。 */
    private fun itemRow(activity: Activity, title: String, sub: String, copyable: Boolean): View {
        val vpad = (activity.resources.displayMetrics.density * 6).toInt()
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, vpad, 0, vpad)
            addView(TextView(activity).apply { text = title; textSize = 14f })
            addView(TextView(activity).apply {
                text = if (copyable) "$sub(点按复制)" else sub
                textSize = 12f
            })
            if (copyable) setOnClickListener { copy(activity, sub, "反馈编号已复制") }
        }
    }

    private fun copy(ctx: Context, text: String, toast: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("feedback_id", text))
            Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
}
