package com.eyecontrol.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面:三项权限引导(摄像头/悬浮窗/无障碍)+ 设置(连眨下数、灵敏度)+ 开始/停止。
 * 全自动检测,无需校准;设置项都是可选微调。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var camStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var a11yFixHint: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var hint: TextView
    // 权限卡折叠:全就绪→只显 permHeader(一行「一切就绪」),否则→显 permDetail(三项明细)。
    // 纯 UI 显隐,依据 refresh() 里既有的 hasCamera()/hasOverlay()/a11yState() 结果,不新增判定。
    private lateinit var permHeader: View
    private lateinit var permDetail: View
    private var permExpanded = false   // 用户手动点开折叠卡后置 true,本次可见期内保持展开

    // 乐观运行态:用户刚点开始(=true)/停止(=false)、但 service 异步起停尚未回信时的"期望态"。
    // service 起停都是异步的,单看 CameraService.running 快照会滞后 → 按钮不跟手。这里先按用户意图
    // 立刻切按钮,等 ACTION_STATE 广播回来、真实态与期望一致时清空(交回真相接管)。null=无待定意图。
    private var pendingRunning: Boolean? = null

    // 监听 CameraService 的运行态广播,收到即 refresh():这是"按钮态最终与真实态一致"的权威校正。
    private val stateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == CameraService.ACTION_STATE) refresh()
        }
    }
    private var stateRegistered = false

    // 无障碍三态:未开启 / 恢复中(已开但系统尚未重绑,常见于重装后) / 就绪
    private enum class A11yState { NOT_ENABLED, RECONNECTING, READY }

    companion object {
        /**
         * 无障碍连接态静态判定,返回 "NOT_ENABLED" / "RECONNECTING" / "READY"。
         * 抽成静态是为了让 Diagnostics(反馈诊断快照)复用【同一份判定逻辑】,
         * 避免两处漂移。判定细节见 MainActivity.a11yState() 的注释。
         */
        fun a11yStateOf(ctx: Context): String {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val me = "${ctx.packageName}/${SwipeAccessibilityService::class.java.name}"

            val enabledSetting = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            var inSetting = false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledSetting)
            for (s in splitter) if (s.equals(me, ignoreCase = true)) { inSetting = true; break }
            if (!inSetting || !am.isEnabled) return "NOT_ENABLED"

            val running = try {
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .any {
                        val si = it.resolveInfo?.serviceInfo ?: return@any false
                        si.packageName == ctx.packageName &&
                            si.name == SwipeAccessibilityService::class.java.name
                    }
            } catch (_: Exception) { false }

            return if (running) "READY" else "RECONNECTING"
        }
    }

    // 自动恢复轮询:进 App 时若发现"已开但没绑上",系统会在数秒内自动重绑,
    // 这里静默轮询、绑上就转 ✅,全程不需要用户动手。
    private val ui = Handler(Looper.getMainLooper())
    private var recoverPoll: Runnable? = null
    private var recoverDeadline = 0L

    private val camPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        camStatus = findViewById(R.id.camStatus)
        overlayStatus = findViewById(R.id.overlayStatus)
        a11yStatus = findViewById(R.id.a11yStatus)
        a11yFixHint = findViewById(R.id.a11yFixHint)
        startBtn = findViewById(R.id.startBtn)
        stopBtn = findViewById(R.id.stopBtn)
        hint = findViewById(R.id.hint)
        permHeader = findViewById(R.id.permHeader)
        permDetail = findViewById(R.id.permDetail)
        // 点折叠的「一切就绪」行 → 展开三项明细(本次可见期保持,下次回前台若仍全就绪再收起)
        permHeader.setOnClickListener {
            permExpanded = true
            refresh()
        }

        findViewById<Button>(R.id.camBtn).setOnClickListener {
            camPermLauncher.launch(Manifest.permission.CAMERA)
        }
        findViewById<Button>(R.id.overlayBtn).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        findViewById<Button>(R.id.a11yBtn).setOnClickListener {
            Toast.makeText(this, "在列表里找到「眨眼控制 · 滑动」并开启", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        setupSettings()

        // 当前版本 + 手动检查更新
        findViewById<TextView>(R.id.versionLabel).text =
            "${getString(R.string.version_prefix)}${BuildConfig.VERSION_NAME}"
        findViewById<Button>(R.id.updateBtn).setOnClickListener { checkUpdate(manual = true) }
        findViewById<Button>(R.id.feedbackBtn).setOnClickListener { FeedbackDialog.show(this) }

        startBtn.setOnClickListener { startDetection() }
        stopBtn.setOnClickListener {
            // 乐观置停:立刻按用户意图切按钮(禁停止、复位开始),不等 onDestroy 回信——它是异步的。
            pendingRunning = false
            startService(Intent(this, CameraService::class.java).setAction(CameraService.ACTION_STOP))
            refresh()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 匿名统计同意门:首启弹一次;同意后记一次 app_open(每设备每天一次,内部节流)。
        // 未同意=零采集;无论如何都不影响功能。埋点旁路,不阻塞主流程。
        ConsentDialog.ensure(this) { granted ->
            if (granted) Analytics.trackAppOpen(this)
        }
    }

    override fun onResume() {
        super.onResume()
        registerStateReceiver()   // 先注册再 refresh:可见期内漏不掉任何一次运行态变化广播
        refresh()
        // 【仅 debug】全自主测试入口:adb 用 `am start ... --ez autostart true` 拉起本界面时,
        // 由 Activity 前台化解冻进程 + 自动点"开始"起前台服务(shell 起不了 exported=false 的服务,
        // 且冷进程收不到广播——见 Greezer)。服务起来钉住进程后,DEBUG_BLINK 注入广播才能送达。
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("autostart", false) == true) {
            intent.removeExtra("autostart")   // 防 onResume 重入重复起
            if (!CameraService.running) {
                android.util.Log.i("DebugBlink", "【调试·autostart】自动启动检测服务")
                startDetection()
            }
        }

        maybeAutoCheckUpdate()

        // 日活埋点:每次回到前台都尝试记一次 app_open(内部按"每设备每天一次"节流,
        // 且未同意直接零采集)。放这里能覆盖"同意后隔天再打开"的日子,不只首启。
        Analytics.trackAppOpen(this)

        // 反馈队列前台补发:每次回前台扫本地队列,有网就把离线/超时留下的反馈补发出去。
        // 全程 daemon + 静默,灰度(令牌空)直接跳过留队列。这是"离线也能发、绝不失败"的补发钩子之一。
        Feedback.flushQueue(this)
    }

    /** 启动时静默检查:每天最多一次(记日期戳);仅发现新版才弹框,无新版/失败不打扰。 */
    private fun maybeAutoCheckUpdate() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (Prefs.lastUpdateCheckDay(this) == today) return   // 今天已查过
        Prefs.setLastUpdateCheckDay(this, today)              // 无论结果都记,避免同日反复请求
        checkUpdate(manual = false)
    }

    /**
     * 检查更新。manual=true 为用户点按钮(全程给反馈:检查中/已最新/失败);
     * manual=false 为启动静默检查(只在有新版时弹框,其余静默)。
     * 回调在子线程,统一 post 回主线程再碰 UI。
     */
    private fun checkUpdate(manual: Boolean) {
        val btn = findViewById<Button>(R.id.updateBtn)
        if (manual) { btn.isEnabled = false; btn.text = "检查中…" }

        UpdateChecker.checkNow { info ->
            ui.post {
                if (manual) { btn.isEnabled = true; btn.text = "检查更新" }
                if (isFinishing || isDestroyed) return@post

                if (info == null) {
                    if (manual) Toast.makeText(this, "检查失败,请稍后再试", Toast.LENGTH_SHORT).show()
                    return@post
                }
                if (!UpdateChecker.isNewer(info.tag, BuildConfig.VERSION_NAME)) {
                    if (manual) Toast.makeText(this, "已是最新版本", Toast.LENGTH_SHORT).show()
                    return@post
                }
                showUpdateDialog(info)
            }
        }
    }

    /** 发现新版:弹框展示版本+更新说明,用户选「去更新」则下载并安装。 */
    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        val notes = if (info.notes.isBlank()) "" else "\n\n更新内容:\n${info.notes.take(500)}"
        MaterialAlertDialogBuilder(this)
            .setTitle("发现新版本 ${info.tag}")
            .setMessage("当前版本 v${BuildConfig.VERSION_NAME},可更新到 ${info.tag}(来源:${info.source})。$notes")
            .setPositiveButton("去更新") { _, _ -> downloadAndInstall(info) }
            .setNegativeButton("以后再说", null)
            .show()
    }

    /** 下载新版 APK(进度弹框)→ 完成后拉起系统安装界面。 */
    private fun downloadAndInstall(info: UpdateChecker.UpdateInfo) {
        val dlg = ProgressDialog(this).apply {
            setTitle("正在下载更新")
            setMessage("请稍候…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }
        UpdateDownloader.download(
            this, info.downloadUrl,
            onProgress = { pct -> ui.post {
                if (pct < 0) dlg.isIndeterminate = true else dlg.progress = pct
            } },
            onDone = { file -> ui.post {
                dlg.dismiss()
                if (isFinishing || isDestroyed) return@post
                try {
                    UpdateDownloader.installApk(this, file)
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "拉起安装失败: ${e.message}")
                    Toast.makeText(this, "无法打开安装界面,请重试", Toast.LENGTH_LONG).show()
                }
            } },
            onError = { msg -> ui.post {
                dlg.dismiss()
                if (isFinishing || isDestroyed) return@post
                MaterialAlertDialogBuilder(this)
                    .setTitle("下载失败")
                    .setMessage("更新包下载失败($msg)。可稍后重试,或到项目页面手动下载。")
                    .setPositiveButton("重试") { _, _ -> downloadAndInstall(info) }
                    .setNegativeButton("取消", null)
                    .show()
            } },
        )
    }

    private fun setupSettings() {
        val nextLabel = findViewById<TextView>(R.id.nextLabel)
        val holdLabel = findViewById<TextView>(R.id.holdLabel)
        val nextSeek = findViewById<SeekBar>(R.id.nextSeek)   // 0..4 → 2..6 下
        val holdSeek = findViewById<SeekBar>(R.id.holdSeek)   // 0..12 → 400..1000ms(步进50)

        nextSeek.progress = Prefs.nextN(this) - 2
        holdSeek.progress = ((Prefs.holdMs(this) - 400L) / 50L).toInt().coerceIn(0, 12)
        nextLabel.text = "下一个 = 连眨 ${Prefs.nextN(this)} 下"
        holdLabel.text = "上一个 = 闭眼保持 ${Prefs.holdMs(this)} 毫秒"

        nextSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                val n = p + 2; Prefs.setNextN(this@MainActivity, n)
                nextLabel.text = "下一个 = 连眨 $n 下"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        holdSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                val ms = 400L + p * 50L; Prefs.setHoldMs(this@MainActivity, ms)
                holdLabel.text = "上一个 = 闭眼保持 $ms 毫秒"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val sensGroup = findViewById<RadioGroup>(R.id.sensGroup)
        when (Prefs.sens(this)) {
            "low" -> sensGroup.check(R.id.sensLow)
            "high" -> sensGroup.check(R.id.sensHigh)
            else -> sensGroup.check(R.id.sensAuto)
        }
        sensGroup.setOnCheckedChangeListener { _, id ->
            Prefs.setSens(this, when (id) {
                R.id.sensLow -> "low"; R.id.sensHigh -> "high"; else -> "auto"
            })
        }
    }

    private fun startDetection() {
        if (!hasCamera()) { Toast.makeText(this, "请先授权摄像头", Toast.LENGTH_SHORT).show(); return }
        if (!hasOverlay()) { Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show(); return }
        when (a11yState()) {
            A11yState.NOT_ENABLED -> { Toast.makeText(this, "请先开启无障碍", Toast.LENGTH_SHORT).show(); return }
            A11yState.RECONNECTING -> { Toast.makeText(this, "无障碍正在自动恢复,请稍候…", Toast.LENGTH_SHORT).show(); return }
            A11yState.READY -> { /* 放行 */ }
        }

        val intent = Intent(this, CameraService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        // 不再 moveTaskToBack:那会让系统判定"后台用相机"而掐断摄像头(尤其澎湃OS/MIUI)。
        // 悬浮窗里那块相机预览会保住"App 可见",用户手动切到抖音即可,相机不掉。
        // 乐观置起:立刻按用户意图切按钮(禁开始、启用停止),不等 onStartCommand 回信——它是异步的。
        pendingRunning = true
        Toast.makeText(this, "已开始!切到抖音 / 快手,连眨即可翻页", Toast.LENGTH_LONG).show()
        refresh()
    }

    // ---- 权限状态 ----
    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasOverlay() = Settings.canDrawOverlays(this)

    /**
     * 无障碍三态判定。要害:重装/更新后 HyperOS 常把服务打成 crashed——
     * 此时它【仍在】enabled_accessibility_services 里(用户开关没丢),但系统没真正绑上,
     * 连眨命中会静默无反应。只读设置字符串会误报"已就绪"。
     *
     * - 是否"意图开启":读 ENABLED_ACCESSIBILITY_SERVICES 设置字符串(含我们 = 用户开过)。
     * - 是否"真正在跑":读 getEnabledAccessibilityServiceList(系统只返回当前真正绑定的服务,
     *   crashed 的会被排除)。二者之差 = "已开但故障(恢复中)"。
     */
    private fun a11yState(): A11yState = when (a11yStateOf(this)) {
        "NOT_ENABLED" -> A11yState.NOT_ENABLED
        "RECONNECTING" -> A11yState.RECONNECTING
        else -> A11yState.READY
    }

    private fun refresh() {
        val ok = getString(R.string.status_ready)          // ✅ 已就绪
        val no = getString(R.string.status_not_enabled)    // ⛔ 未开启
        val green = ContextCompat.getColor(this, R.color.success)
        val gray = ContextCompat.getColor(this, R.color.textSecondary)
        val amber = ContextCompat.getColor(this, R.color.warn)

        camStatus.text = if (hasCamera()) ok else no
        camStatus.setTextColor(if (hasCamera()) green else gray)
        overlayStatus.text = if (hasOverlay()) ok else no
        overlayStatus.setTextColor(if (hasOverlay()) green else gray)

        val a11y = a11yState()
        when (a11y) {
            A11yState.READY -> {
                a11yStatus.text = ok; a11yStatus.setTextColor(green)
                a11yFixHint.visibility = View.GONE
                stopRecoverPoll()
            }
            A11yState.NOT_ENABLED -> {
                a11yStatus.text = no; a11yStatus.setTextColor(gray)
                a11yFixHint.visibility = View.GONE
                stopRecoverPoll()
            }
            A11yState.RECONNECTING -> {
                // 已开但系统尚未绑上(多见于重装/更新后)。打开本 App 会触发系统在数秒内自动重绑,
                // 这里静默轮询、绑上就转 ✅,不打扰用户、不需要任何手动操作。
                a11yStatus.text = getString(R.string.status_reconnecting)
                a11yStatus.setTextColor(amber)
                a11yFixHint.visibility = View.VISIBLE
                startRecoverPoll()
            }
        }

        val allReady = hasCamera() && hasOverlay() && a11y == A11yState.READY

        // 权限卡折叠:全就绪且用户未手动展开 → 折叠成一行「一切就绪」;否则展开三项明细。
        // 未全就绪时强制展开(得让用户看到缺哪项),并复位手动展开标记。
        if (!allReady) permExpanded = false
        val collapsed = allReady && !permExpanded
        permHeader.visibility = if (collapsed) View.VISIBLE else View.GONE
        permDetail.visibility = if (collapsed) View.GONE else View.VISIBLE

        // 真实态一旦追上用户意图,乐观标记就功成身退,交回真相接管(避免真相变化后被旧意图钉死)。
        if (pendingRunning == CameraService.running) pendingRunning = null

        // 生效运行态:有待定意图先认意图(按钮跟手),否则认服务真实态。effectiveRunning 为准切按钮/提示。
        val effectiveRunning = pendingRunning ?: CameraService.running

        // 底部主操作 + 动态提示(语义与旧版一致:未就绪禁用、运行中显停止态)。
        startBtn.isEnabled = allReady && !effectiveRunning
        stopBtn.isEnabled = effectiveRunning
        hint.text = when {
            !allReady -> getString(R.string.hint_need_grant)
            effectiveRunning -> getString(R.string.hint_running)
            else -> getString(R.string.hint_ready)
        }
    }

    /**
     * 静默自动恢复:进 App 发现无障碍"已开但没绑上"时,系统正因本 App 前台化而重绑(实测约 3 秒)。
     * 每 800ms 复查一次,绑上即刷新为 ✅;给 8 秒宽限,足够覆盖实测最慢重绑。
     * 全程无需用户参与——这是产品对普通大众的承诺。
     */
    private fun startRecoverPoll() {
        if (recoverPoll != null) return               // 已在轮询,不重复起
        recoverDeadline = android.os.SystemClock.uptimeMillis() + 8000L
        recoverPoll = object : Runnable {
            override fun run() {
                if (a11yState() == A11yState.READY) { recoverPoll = null; refresh(); return }
                if (android.os.SystemClock.uptimeMillis() > recoverDeadline) {
                    // 8 秒仍未自动恢复(实测未遇到)。不吓用户,只把说明留在原地,继续轻量重试。
                    recoverDeadline = android.os.SystemClock.uptimeMillis() + 8000L
                }
                ui.postDelayed(this, 800L)
            }
        }
        ui.postDelayed(recoverPoll!!, 800L)
    }

    private fun stopRecoverPoll() {
        recoverPoll?.let { ui.removeCallbacks(it) }
        recoverPoll = null
    }

    override fun onPause() {
        super.onPause(); stopRecoverPoll()
        unregisterStateReceiver()
        permExpanded = false   // 离开界面后复位:下次回来若仍全就绪,权限卡重新收起为一行
    }
    override fun onDestroy() { super.onDestroy(); stopRecoverPoll() }

    /** 注册运行态广播接收器(内部广播、不导出,与 CameraService 的 swipeStatReceiver 同模式)。失败静默。 */
    private fun registerStateReceiver() {
        if (stateRegistered) return
        try {
            val filter = android.content.IntentFilter(CameraService.ACTION_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(stateReceiver, filter)
            }
            stateRegistered = true
        } catch (_: Exception) {}
    }

    private fun unregisterStateReceiver() {
        if (!stateRegistered) return
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        stateRegistered = false
    }
}
