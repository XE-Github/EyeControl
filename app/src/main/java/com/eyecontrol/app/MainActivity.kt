package com.eyecontrol.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
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

    // 无障碍三态:未开启 / 恢复中(已开但系统尚未重绑,常见于重装后) / 就绪
    private enum class A11yState { NOT_ENABLED, RECONNECTING, READY }

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
            Toast.makeText(this, "在列表里找到「眨眼控制·滑动」并开启", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        setupSettings()

        // 当前版本 + 手动检查更新
        findViewById<TextView>(R.id.versionLabel).text = "当前版本 v${BuildConfig.VERSION_NAME}"
        findViewById<Button>(R.id.updateBtn).setOnClickListener { checkUpdate(manual = true) }

        startBtn.setOnClickListener { startDetection() }
        stopBtn.setOnClickListener {
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
        super.onResume(); refresh()
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
        AlertDialog.Builder(this)
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
                AlertDialog.Builder(this)
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
            A11yState.RECONNECTING -> { Toast.makeText(this, "无障碍正在自动恢复，请稍候…", Toast.LENGTH_SHORT).show(); return }
            A11yState.READY -> { /* 放行 */ }
        }

        val intent = Intent(this, CameraService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        // 不再 moveTaskToBack:那会让系统判定"后台用相机"而掐断摄像头(尤其澎湃OS/MIUI)。
        // 悬浮窗里那块相机预览会保住"App 可见",用户手动切到抖音即可,相机不掉。
        Toast.makeText(this, "已开始！悬浮窗出现后，手动切到抖音/快手连眨即可", Toast.LENGTH_LONG).show()
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
    private fun a11yState(): A11yState {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val me = "$packageName/${SwipeAccessibilityService::class.java.name}"

        // ① 用户是否开过(设置字符串)
        val enabledSetting = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        var inSetting = false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledSetting)
        for (s in splitter) if (s.equals(me, ignoreCase = true)) { inSetting = true; break }
        if (!inSetting || !am.isEnabled) return A11yState.NOT_ENABLED

        // ② 是否真正绑定在跑(排除 crashed)
        val running = try {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any {
                    val si = it.resolveInfo?.serviceInfo ?: return@any false
                    si.packageName == packageName &&
                        si.name == SwipeAccessibilityService::class.java.name
                }
        } catch (_: Exception) { false }

        // 已开但没绑上 = 重装后的 crashed 态,系统会在打开本 App 后数秒内自动重绑。
        return if (running) A11yState.READY else A11yState.RECONNECTING
    }

    private fun refresh() {
        val ok = "✅ 已就绪"; val no = "⛔ 未开启"
        val green = 0xFF3FB950.toInt(); val gray = 0xFF8B949E.toInt(); val amber = 0xFFD29922.toInt()

        camStatus.text = if (hasCamera()) ok else no
        overlayStatus.text = if (hasOverlay()) ok else no

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
                a11yStatus.text = "🔄 正在自动恢复…"; a11yStatus.setTextColor(amber)
                a11yFixHint.visibility = View.VISIBLE
                startRecoverPoll()
            }
        }

        val allReady = hasCamera() && hasOverlay() && a11y == A11yState.READY
        startBtn.isEnabled = allReady && !CameraService.running
        stopBtn.isEnabled = CameraService.running
        startBtn.text = if (allReady) "▶ 开始（缩到后台检测）" else "▶ 开始（先完成上面三项授权）"
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

    override fun onPause() { super.onPause(); stopRecoverPoll() }
    override fun onDestroy() { super.onDestroy(); stopRecoverPoll() }
}
