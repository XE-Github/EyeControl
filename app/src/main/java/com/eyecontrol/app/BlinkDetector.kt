package com.eyecontrol.app

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 眨眼检测器 —— 从已验证的 Web Demo(poc-web/index.html)1:1 移植。
 *
 * 输入:每帧的人脸关键点(归一化坐标, x/y ∈ [0,1], 与 MediaPipe 一致)+ 单调递增时间戳(ms)。
 * 输出:通过 [Listener] 回调"就绪 / 睁闭状态 / 连眨命中下一个/上一个"。
 *
 * 算法层与 Web 完全等价:EAR + 双速自适应基线 + AGC 动态判定线 + 峰谷状态机 + 连眨渐弱补偿 +
 * 漂移自愈 + 突变过渡放宽。参数常量与 Web 端保持一致,确保效果等同已验证版本。
 *
 * 本类不依赖任何 Android API(时间戳由外部传入),便于复用与测试。
 */
class BlinkDetector(private val listener: Listener) {

    interface Listener {
        /** 首次就绪(基线学好) */
        fun onReady()
        /** 睁/闭状态变化, open=true 睁眼 */
        fun onEyeState(open: Boolean)
        /** 连眨计数变化(0=清空), n=当前已累计的连眨下数 */
        fun onCount(n: Int)
        /** 命中"下一个"(快速连眨达到阈值 nextN) */
        fun onNext()
        /** 命中"上一个"(闭眼保持达到 holdMs) */
        fun onPrev()
        /** 闭眼保持进度 fraction∈[0,1],用于悬浮窗进度条(睁眼/命中后回 0) */
        fun onHoldProgress(fraction: Float) {}
        /** 环境突变自动重校准(仅用于日志/调试) */
        fun onRecalibrated() {}
    }

    // ---- 眼睛 6 点索引(与 Web 同款)----
    // 每只眼:p1,p4 为水平端点;p2,p6 / p3,p5 为两组竖直端点。
    private val LEFT = intArrayOf(33, 160, 158, 133, 153, 144)   // p1..p6
    private val RIGHT = intArrayOf(362, 385, 387, 263, 373, 380)

    // ---- 可调参数(默认与 Web 一致)----
    // 下一个 = 快速连眨"≥ nextN 下"(阈值,非精确匹配:多眨仍是下一个,绝不翻反)。
    var nextN = 3
    // 上一个 = 闭眼保持 ≥ holdMs 毫秒(另一条动作轴,任何眨眼计数都到不了 → 结构上不可能翻反)。
    // 不变式:maxDur(400) < holdMs(550) < DRIFT_HEAL_MS(900)。
    //   - > maxDur:保证"眨眼"与"保持"分属两段时长,中间 400~550ms 是死区(既非眨眼也非保持)。
    //   - < DRIFT_HEAL_MS:保持先命中,drift 自愈才不会抢在前面误判环境突变。
    // 真人反馈 800ms 太慢 → 降到 550ms 更跟手;同时把 maxDur 从 500 降到 400 维持死区间隔。
    var holdMs = 550L
    // 旧的"上一个=连眨 prevN 下"已退役(相邻计数会被杂散眨眼翻反)。字段不再参与判定,
    // 仅 Prefs 迁移期读一次老值。保留以免误用,勿在状态机里读它。
    /** 灵敏度偏置: auto=0, low=+0.06(偏稳), high=-0.06(偏灵敏) */
    var bias = 0.0
        set(v) { field = v; recomputeLines() }

    private val minDur = 40.0        // 单次眨眼最短时长(ms)
    private val maxDur = 400.0       // 单次眨眼最长时长;400~holdMs(550)之间是死区,超 holdMs=闭眼保持
    // 连眨合并窗:一组最后一下后等这么久没有新眨眼就【超时收尾】。注意:够 nextN 下时不再等这个窗口
    // (见 onBlink 的即时触发),window 只用于"没够数的零散眨眼"多久清零。真人自然连眨相邻间隔可达
    // ~400ms,window 不能压到那以下否则会把真连眨截断成两组(眨了3下却当成2+1都不触发)。600ms 安全。
    private val window = 600L
    private val cooldown = 250L      // 整组触发后冷却(400→250,缩短两次操作间的等待)
    // 组内相邻两下的最小【起点】间隔(见 onBlink:按起点而非结束点去重)。
    // 人最快自主眨眼周期 ~200ms(起点到起点);rebounded 把一次眨眼谷底抖动切成两段时,
    // 第二段的伪起点紧贴第一段(实测 ~40~120ms)。130ms 卡在两者之间:
    // 真两下(≥200ms)全计数,抖动切分(<130ms)丢弃。
    // (旧值 180L 是按【结束点】间隔设的,会误伤"起点分得开但各自很短"的真快连眨 → 眨3显2,已修。)
    private val minGapInGroup = 130L
    // 判定线【硬下限】= 兜底,不是工作点。真正的工作判定线由自适应 floor=noiseUp*1.6+0.03 决定
    // (按每个人实测的睁眼噪声算),minEnter 只在噪声被病态学到≈0 时兜底。
    // 历史:曾从 0.10 抬到 0.18,是为压制"睁眼误判闭眼的恶性正反馈"——当年根因是【闭眼帧把基线往下拉】
    // (baseWin 采样耦合 inBlink),假闭眼→基线塌→判定线塌→更多假闭眼。
    // 那条腐蚀通道已被后续重构【结构性铲除】:baseline 现在跟踪 rawWin(全帧,不受 inBlink 门控)的
    // 0.65 高分位,假 ENTER 的 inBlink 标志【根本不进基线方程】,拉不动 baseline;noiseUp 采样门
    // (!inBlink && dropNow<dynExit*0.7)也挡住"眨眼深度/假闭眼帧"被学成噪声。死循环两个反馈节点都断了。
    // (2026-07-30 独立审计逐行核实 line 333/350/363-365/371-375/424-425。)
    // 真机实测【浅眨用户】:睁眼 EAR≈0.22、眨眼谷≈0.18,眨眼 drop 仅 0.13~0.18,而 0.18 硬地板把本已
    // 算好的自适应线(~0.09)强行抬到 0.18 之上 → 浅眨全被挡在门外(眨3记1/2 的第三层真根因)。
    // 降到 0.10:仍接住该用户最浅的 0.13 眨眼(余量 +0.03),又能挡住睁眼微抖/跟踪尖刺(≤~0.08);
    // 深眨用户 noiseUp 大、工作线由 target(~0.16)决定,minEnter 从不触底,行为不变。
    private val minEnter = 0.10
    private val maxEnter = 0.45

    // ---- 自适应基线 + AGC ----
    private var baseline = Double.NaN
    private var ready = false
    /** 【测试用】当前是否已就绪(基线学好)。供注入 harness 量学习时间。 */
    fun isReady(): Boolean = ready
    private var noiseUp = 0.05       // 睁眼噪声上沿
    private var peakDrop = 0.30      // 眨眼峰值降幅(逆光自动变小)
    private var dynEnter = 0.30
    private var dynExit = 0.18

    private val noiseWin = ArrayDeque<DropSample>()
    private val BASE_WIN_MS = 4000L
    // 原始 EAR 近窗:记录最近 RAW_WIN_MS 内【所有】帧的 ear,【完全不受 inBlink 门控】。
    // 这是"睁眼水平"的真相源(解决 baseWin 因 !inBlink 采样门在误判死锁期冻结成旧高值的中毒问题):
    // 一段时间里睁眼帧占多数且 ear 更高,取其【高分位】(RAW_OPEN_Q)天然落在"睁眼区"——
    // 偶尔的眨眼谷是少数低值,落在低分位,不拉低高分位。baseline 平滑朝这个高分位收敛(双向),
    // 于是初始学高也能追降到真实睁眼水平,而单次眨眼/短闭不影响(它们进不了高分位)。
    private val rawWin = ArrayDeque<Sample>()
    private val RAW_WIN_MS = 3000L
    private val RAW_OPEN_Q = 0.65   // 高分位:视作"睁眼水平"的分位点(睁眼帧多数 → 高分位在睁眼区)
    // 就绪所需的最少"睁眼"样本。20→12:30fps 下约 0.4s 睁眼帧即可就绪,明显缩短"学习中"等待
    // (真人反馈学习时间长)。12 个样本做中位数对"睁眼基线"已足够稳;偏低样本靠后续持续
    // 更新(est>baseline 慢收敛 + 噪声分位)自我校正,不牺牲判定质量。
    private val BASE_MIN_SAMPLES = 12

    // ---- 峰谷状态机 ----
    private var inBlink = false
    private var blinkStartTs = 0.0
    private var blinkPeakDrop = 0.0
    private var blinkPeakTs = 0.0
    private var lastEyeOpen = true
    // 闭眼保持:一次闭眼里 holdMs 到点只触发一次"上一个",直到重新睁眼才复位。
    private var holdFired = false
    // 【治"眨眼瞬时丢脸清空连眨组",2026-07-30】丢脸起点时刻(0=当前没在丢脸)。
    // 真机实证:闭眼那几帧最易让 MediaPipe 瞬时丢脸(presence 掉线),旧 onNoFace 每帧就把
    // inBlink/连眨组/PREV武装全清 → 一串快眨中间丢一两帧脸就把已数到的 1/2 抹掉 → 停在 1/2。
    // 加短容忍窗 NOFACE_GRACE_MS:丢脸时长在此窗内,视作"眨眼瞬态丢帧",【什么都不清】,
    // 让脸回来后连眨串/inBlink 无缝接续;只有持续丢脸超过它(人真离开/长时间转头)才降级清状态。
    private var noFaceSinceTs = 0.0
    private val NOFACE_GRACE_MS = 220.0   // > 一次眨眼谷(100~150ms),覆盖闭眼期整段瞬时丢脸
    // "上一个"武装:仅当本次闭眼是从"稳定睁眼"跌入时才允许保持命中。
    // readyTs=基线学好的时刻;lastStableOpenTs=最近一次确认睁眼的时刻。
    // 治启动误触发:检测刚就绪时若眼睛正闭着/半闭(基线绕着低 EAR 学成),
    // 一次本就很长的初始闭眼会越过 holdMs 直接误发"上一个"。
    // 要求"闭眼起点之前,眼睛已确认睁开并稳定 ≥ PREV_ARM_MS"才武装,把这种启动态挡在门外。
    private var readyTs = 0.0
    private var lastStableOpenTs = 0.0
    private var prevArmed = false
    private val PREV_ARM_MS = 500.0   // 闭眼起点前需已稳定睁眼的最短时长

    // ---- 连眨串 & 突变过渡 ----
    private var streakFirstPeak = 0.0
    private var streakLastTs = 0.0
    private val STREAK_GAP_MS = 900.0
    private var relaxUntil = 0.0
    private val RELAX_MS = 1200.0

    // ---- 连眨计数(命中触发)----
    private val blinkGroup = ArrayList<Long>()
    private var groupDeadline = 0L
    private var lastTriggerTs = 0L
    // 【治任务#44 抖音尖刺深坑"眨3停在1"的判定侧根因,2026-08-03】组从第一下起的墙钟起点。
    // 深坑期 procMs 飙到 2007ms → 单帧间隔一次跨过整个 window(600ms)→ 第2下还没来,一帧就
    // 触发 groupDeadline 收尾清零(finalizeGroup 的 else 分支 onCount(0))→ 真连眨被冲掉、停在1。
    // 治法(B路,纯 detector 内、零管线风险):onEar 每帧算与上帧的间隔 gap,尖刺(gap 大)时把
    // groupDeadline【按丢失的墙钟时间顺延】——尖刺吞掉的那段时间不该算进"没新眨眼"的超时里。
    // 但顺延需绝对封顶(GROUP_MAX_LIFETIME_MS),防病态永不收尾:组活过这个上限一律强制收尾。
    private var groupStartTs = 0L
    // 采样稀疏阈值:常态帧间隔 HIGH33/LOW66 + procMs ~50ms → 健康 <80ms;80-150ms 属可接受抖动;
    // 150ms(≈window 1/4)卡在"健康抖动"与"真尖刺(150-2000ms)"之间,不误判常态帧。
    private val SPARSE_GAP_MS = 150L
    // 顺延语义(2026-08-03 harness spike3 实证修正):大 gap 那帧到达时,把【整段静默 gap】从超时里
    // 平移掉——这段时间根本没采到帧、没有检测机会,不该算作"没有新眨眼的等待"。deadline += gap。
    // (先前"扣 NOMINAL 后小封顶 600ms"的写法被 spike3 证伪:真实深坑 gap 1800~2007ms 远超 600ms 封顶,
    //  顺延后 deadline 仍落在尖刺帧之前 → 组照样被清 → 停在2。整段平移才压得住真实深坑。)
    // 不必再设单帧小封顶:真正的兜底是下面的组绝对寿命 GROUP_MAX_LIFETIME_MS,防病态无限顺延。
    // 组从第一下起的绝对寿命上限:再怎么顺延,活过这个就强制收尾(真连眨在正常/尖刺下都远快于此)。
    private val GROUP_MAX_LIFETIME_MS = 2500L

    // ---- 自适应帧率信号(治任务#30 降帧引入的"眨3记2")----
    // FaceAnalyzer 以此决定采样帧率:安静时 ~15fps 省电,眼睛一有动静就临时提回 ~30fps,
    // 保证快眨的短谷(100~150ms)每下都采够帧、不漏 ENTER。@Volatile 供分析线程跨读。
    // 治根:66ms 单档下快眨谷只落 1~2 帧,恰好落肩部(未到判定深度)就整下漏掉 → 3 记 2。
    @Volatile var highRateUntilTs = 0L
        private set
    // 提速"预门槛":远低于 minEnter(0.18),任何轻微 EAR 下沉(含还没到判定深度的下降/上升肩部)
    // 就武装提速——目的是【第一帧刚有动静】就提速,让同一次眨眼后续帧+整串连眨都被 30fps 采到。
    // 略高于睁眼噪声 noiseUp(常态 ~0.05)避免常驻高帧空耗电。
    private val BOOST_ARM_DROP = 0.06
    // 提速 latch 时长:> STREAK_GAP_MS(900),覆盖连眨串内相邻眨眼间隔;安静 >1s 才回落省电档。
    private val BOOST_HOLD_MS = 1000L

    // ---- 漂移自愈 ----
    private var driftSince = 0.0
    private var lastHealthLogTs = 0L   // 【仅 DEBUG】每秒健康诊断节流
    private val DRIFT_HEAL_MS = 900.0
    // 超长异常闭眼:一次"闭眼合"持续超过这个时长,就不再当它是有意手势(眨眼 dur≤400、
    // 保持 550),而是真环境突变(逆光/大幅移动让睁眼态 EAR 都塌到判定线下,状态机困在 inBlink
    // 出不来)。只有这种"卡死的长闭"才允许在 inBlink 期间继续累积 drift 计时并自愈。
    private val DRIFT_STUCK_MS = 2000.0

    // "上一个"绝对闭眼门:真闭眼 = EAR 相对【睁眼水平真相源】显著下沉(绝对量),
    // 而不是相对滞后 baseline 的大 drop。治"睁眼水平下移(距离/角度/放松)时 EAR 只是
    // 沉到较低的睁眼位、baseline 一时追不上 → 相对 drop 虚高 → 误发上一个"——
    // 那一刻眼睛其实【是睁着的】,ear≈openLevel(比值接近 1),达不到闭眼深度。
    // 真有意闭眼会把 ear 砸到 openLevel 的 ~40% 以下并保持,比值远低于此。
    // openLevel 取自 rawWin 高分位(不受 inBlink 门控、不被滞后 baseline 带偏),故这道门
    // 对"基线追不上"的漂移免疫,只认真实的绝对闭眼深度。
    private val PREV_CLOSED_RATIO = 0.55   // ear <= openLevel*此值 才算够闭(0.21*0.55≈0.115,真谷~0.08 过、漂移到0.21不过)
    private var lastOpenLevel = Double.NaN  // updateBaseline 每帧算好的睁眼水平,供 hold 门复用(免重算分位)

    private data class Sample(val t: Double, val ear: Double)
    private data class DropSample(val t: Double, val drop: Double)

    /** 复位所有状态(每次开始检测前调用) */
    fun reset() {
        baseline = Double.NaN; ready = false
        noiseUp = 0.05; peakDrop = 0.30; dynEnter = 0.30; dynExit = 0.18
        rawWin.clear(); noiseWin.clear(); lastOpenLevel = Double.NaN
        inBlink = false; blinkPeakDrop = 0.0; blinkPeakTs = 0.0; lastEyeOpen = true
        holdFired = false; noFaceSinceTs = 0.0
        readyTs = 0.0; lastStableOpenTs = 0.0; prevArmed = false
        streakFirstPeak = 0.0; streakLastTs = 0.0; relaxUntil = 0.0
        blinkGroup.clear(); groupDeadline = 0L; lastTriggerTs = 0L
        groupStartTs = 0L
        // 清帧间隔基准:否则 reset 后首帧拿到上一 session 的陈旧 now → gap 算出巨值被误判尖刺、
        // 错误顺延 deadline。清零后 onEar 里 gap 首帧哨兵(lastFrameTs>0 才算)双保险。
        lastFrameTs = 0L
        driftSince = 0.0
        highRateUntilTs = 0L
        maxBlinkStreak = 0   // 纯观测计数器随会话复位(判定无关)
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Double =
        hypot((ax - bx).toDouble(), (ay - by).toDouble())

    /** 单眼 EAR = (竖直距离和) / (2×水平距离) */
    private fun earOf(xs: FloatArray, ys: FloatArray, e: IntArray): Double {
        val vert = dist(xs[e[1]], ys[e[1]], xs[e[5]], ys[e[5]]) +
                dist(xs[e[2]], ys[e[2]], xs[e[4]], ys[e[4]])
        val horiz = 2.0 * dist(xs[e[0]], ys[e[0]], xs[e[3]], ys[e[3]])
        return if (horiz == 0.0) 0.3 else vert / horiz
    }

    /**
     * 每帧调用。
     * @param xs 468(或 478)个关键点的归一化 x
     * @param ys 对应的归一化 y
     * @param now 单调递增时间戳(ms),建议用 SystemClock.uptimeMillis()
     */
    // 【仅测试】注入模式:为 true 时,来自真实相机的 onFrame 一律忽略,
    // 只处理 DebugBlinkReceiver 通过 onEar 回放的脚本序列,避免真假帧交错污染结果。
    @Volatile var injecting = false

    fun onFrame(xs: FloatArray, ys: FloatArray, now: Long) {
        if (injecting) return
        val ear = (earOf(xs, ys, LEFT) + earOf(xs, ys, RIGHT)) / 2.0
        onEar(ear, now)
    }

    /**
     * 【测试注入用】直接喂一个已算好的 EAR 值(绕过关键点几何),走与真实帧完全相同的
     * 基线/状态机/计数路径。debug 包的 DebugBlinkReceiver 用它回放脚本化眨眼序列,
     * 实现"无需真人眨眼"的全自主闭环测试。release 逻辑不依赖它。
     */
    fun onEar(ear: Double, now: Long) {
        // 帧间隔(治任务#44):必须在 lastFrameTs 被覆盖【之前】读旧值。gap==0 → 首帧(reset 后
        // lastFrameTs=0),不放宽,避免陈旧时间戳算出巨值误判尖刺。深坑期这个 gap 会 >150ms(常态
        // ~33-80ms),下面据此把 groupDeadline 按丢失墙钟顺延,别让稀疏帧把真连眨组超时冲掉。
        val gap = if (lastFrameTs > 0L) now - lastFrameTs else 0L
        lastFrameTs = now
        val t = now.toDouble()
        noFaceSinceTs = 0.0   // 收到一帧真脸 → 结束任何丢脸计时(容忍窗从下次丢脸重新起算)

        updateBaseline(t, ear)
        val drop = if (baseline > 0) max(0.0, (baseline - ear) / baseline) else 0.0

        // 自适应提速:眼睛一有动静(EAR 轻微下沉 或 正处于一次闭眼合内)就把高帧率 latch 续到
        // now+BOOST_HOLD_MS,FaceAnalyzer 据此临时提回 ~30fps 保证快眨每下采够。放在 drop 算好后、
        // ready 判定前——首帧下降(baseline 一有值就能算 drop)即可武装,不必等就绪。
        if (drop >= BOOST_ARM_DROP || inBlink) highRateUntilTs = now + BOOST_HOLD_MS

        // 【仅 DEBUG】每秒一次基线健康诊断:无论是否重校准都打,用于区分"睁眼水平渐变(基线追不上)"
        // 与"用户在闭眼"。健康时 ear≈baseline(drop 小、inBlink=false);基线学歪时 ear<<baseline 且长期。
        if (BuildConfig.DEBUG && now - lastHealthLogTs >= 1000L) {
            lastHealthLogTs = now
            android.util.Log.i("DriftDiag",
                "HEALTH ear=${"%.3f".format(ear)} base=${"%.3f".format(baseline)} " +
                "drop=${"%.3f".format(drop)} dynEnter=${"%.3f".format(dynEnter)} dynExit=${"%.3f".format(dynExit)} " +
                "inBlink=$inBlink rawWin=${rawWin.size} ready=$ready")
        }

        // 到期收尾连眨组。任务#44:先按帧间隔补偿尖刺吞掉的墙钟——深坑期一帧可跨过整个 window,
        // 若直接比 now>=deadline 会把"其实只是没采到帧"的真连眨误判成"超时没新眨眼"而清零(停在1)。
        // gap 超过采样稀疏阈值时,把 deadline 顺延"gap 里超出名义节奏的那部分"(单帧封顶一个 window),
        // 相当于把尖刺静默的时间从超时里扣掉;gap 回落 <150ms 自动不再顺延,无状态、自愈。
        if (groupDeadline != 0L && gap > SPARSE_GAP_MS) {
            groupDeadline += gap   // 整段静默平移:尖刺吞掉的时间没有检测机会,不算超时(组寿命封顶兜底)
        }
        // 绝对寿命封顶:再怎么顺延,组活过 GROUP_MAX_LIFETIME_MS 就强制收尾,防病态废组永不清。
        if (groupDeadline != 0L && groupStartTs != 0L && now - groupStartTs > GROUP_MAX_LIFETIME_MS) finalizeGroup()
        if (groupDeadline != 0L && now >= groupDeadline) finalizeGroup()

        if (!ready) return

        // 有效进入门槛:动态线;连眨串中按第一下峰值放宽后续;突变过渡期再放宽。
        var effEnter = dynEnter
        val inStreak = (t - streakLastTs) < STREAK_GAP_MS && streakFirstPeak > 0
        if (inStreak) {
            effEnter = min(effEnter, max(noiseUp * 1.8 + 0.02, streakFirstPeak * 0.40))
        }
        if (t < relaxUntil) effEnter = min(effEnter, max(noiseUp * 1.8 + 0.02, dynEnter * 0.6))
        val effExit = max(noiseUp * 1.2, effEnter * 0.5)

        if (!inBlink) {
            if (drop >= effEnter) {
                inBlink = true; blinkStartTs = t; blinkPeakDrop = drop; blinkPeakTs = t
                holdFired = false
                // 武装"上一个":本次闭眼必须从"稳定睁眼"跌入——即闭眼起点前,
                // 眼睛已确认睁开且持续 ≥ PREV_ARM_MS,且距基线就绪也已过同样的安置期。
                // 否则(启动态、基线刚学好就在闭眼、无脸恢复后立刻闭)不允许保持命中,
                // 避免那次"本就很长的初始闭眼"被当成有意的"上一个"。
                val openStable = lastStableOpenTs > 0 && (t - lastStableOpenTs) >= PREV_ARM_MS
                val settled = readyTs > 0 && (t - readyTs) >= PREV_ARM_MS
                prevArmed = openStable && settled
                setEye(false)
            } else {
                // 记录"最近一次确认睁眼"这一连续段的起点:睁眼边沿(上一帧非睁)或首帧时刷新。
                // 必须在 setEye 之前读 lastEyeOpen(setEye 会改它)。用作下次闭眼的武装依据。
                if (lastStableOpenTs == 0.0 || !lastEyeOpen) lastStableOpenTs = t
                setEye(true)
            }
        } else {
            if (drop > blinkPeakDrop) { blinkPeakDrop = drop; blinkPeakTs = t }
            setEye(false)

            // 闭眼保持 = "上一个"。一次闭眼里到点只触发一次(holdFired),边沿触发:
            // 在眼睛"仍闭着"时就命中(不等睁开),这样能抢在"闭眼发呆 dur>maxDur 不计"之前,
            // 也早于 drift 自愈(holdMs<DRIFT_HEAL_MS)。命中即清连眨组,保持的闭眼绝不算 NEXT。
            val held = t - blinkStartTs
            if (!holdFired) {
                val frac = (held / holdMs.toDouble()).coerceIn(0.0, 1.0)
                listener.onHoldProgress(frac.toFloat())
                val streakTail = (t - streakLastTs) < STREAK_GAP_MS   // 刚眨完一串的尾巴,不当保持
                // 绝对闭眼门:必须真的闭到"睁眼水平的 PREV_CLOSED_RATIO 以下"才算有意保持。
                // 睁眼水平下移(基线追不上)时 ear 只是沉到较低睁眼位,ear≈openLevel 达不到闭眼深度,
                // 这道门把那种"眼其实睁着、相对 drop 虚高"的误命中挡死。openLevel 无效时(冷启动
                // 样本不足)退回旧行为(仅靠相对 drop),不引入新的漏命中。
                val deepEnough = lastOpenLevel.isNaN() || ear <= lastOpenLevel * PREV_CLOSED_RATIO
                if (held >= holdMs && prevArmed && !streakTail && deepEnough && (now - lastTriggerTs) >= cooldown) {
                    holdFired = true
                    blinkGroup.clear(); groupDeadline = 0L; groupStartTs = 0L
                    listener.onCount(0)
                    listener.onHoldProgress(0f)
                    listener.onPrev(); lastTriggerTs = now
                }
            }

            // 结束:①回落到 effExit 以下;或 ②从峰值明显回升(相邻两下之间的谷)
            val rebounded = drop <= blinkPeakDrop * 0.55 && (t - blinkPeakTs) >= 40
            if (drop <= effExit || rebounded) {
                val dur = t - blinkStartTs
                val wasHold = holdFired
                inBlink = false; holdFired = false
                listener.onHoldProgress(0f)
                setEye(drop <= effExit)
                // 已作为"闭眼保持"命中过的这次闭眼,不再登记为连眨(否则会多计一下 NEXT)。
                if (!wasHold && dur in minDur..maxDur && blinkPeakDrop >= effEnter) {
                    // 传入本次闭眼的【起点】blinkStartTs 作组内去重基准(见 onBlink):
                    // 相邻两下是不是"同一下的抖动"要看起点隔多远,而不是结束点。
                    registerBlink(now, blinkStartTs.toLong(), blinkPeakDrop)
                }
            }
        }
    }

    /** 无脸帧:视为睁眼,避免误判 */
    fun onNoFace(now: Long = 0L) {
        // 【仅测试】注入期间,真实相机仍在跑,手机搁桌上"看不到脸"会持续回调 onNoFace,
        // 它每帧把 inBlink 清掉,与注入线程的 onEar 竞争,导致合成眨眼永远进不了状态机。
        // 注入模式下真实相机的一切输入(含无脸帧)都必须让路,交由注入序列独占检测器。
        if (injecting) return

        // 【短容忍窗,2026-07-30 治"眨眼瞬时丢脸清空连眨组"】闭眼那几帧最易让 MediaPipe 瞬时
        // 丢脸(presence 掉线),但那正是一次眨眼/一串快眨的进行中——绝不能因这一两帧丢脸就把
        // 已数到的连眨组、inBlink、PREV 武装全清。丢脸时长在 NOFACE_GRACE_MS 内 → 视作眨眼瞬态、
        // 【原样保留所有状态直接返回】,脸一回来 onEar 无缝接续;只有持续丢脸超过它(人真离开/
        // 长时间转头)才走下面的降级清理。now=0(旧无参调用/未传时钟)则退化为立即降级(保守)。
        val t = now.toDouble()
        if (now > 0L) {
            if (noFaceSinceTs == 0.0) noFaceSinceTs = t
            if (t - noFaceSinceTs < NOFACE_GRACE_MS) return   // 眨眼瞬态丢帧:什么都不动
        }

        if (inBlink) { inBlink = false; holdFired = false; listener.onHoldProgress(0f) }
        // 丢脸后眼睛"重新出现",睁眼稳定计时清零:回来后须重新累积 PREV_ARM_MS
        // 才允许下一次闭眼命中"上一个",避免丢脸恢复瞬间的闭眼被误当保持。
        lastStableOpenTs = 0.0; prevArmed = false
        setEye(true)
    }

    private fun setEye(open: Boolean) {
        if (open != lastEyeOpen) { lastEyeOpen = open; listener.onEyeState(open) }
    }

    // ---- 自适应基线 + 噪声 + 动态判定线 + 漂移自愈(双速) ----
    private fun updateBaseline(now: Double, ear: Double) {
        if (baseline.isNaN()) baseline = ear

        // 原始近窗:每帧都收,不受 inBlink 门控(这是与旧 baseWin 的本质区别,也是治死锁的关键)。
        rawWin.addLast(Sample(now, ear))
        while (rawWin.isNotEmpty() && now - rawWin.first().t > RAW_WIN_MS) rawWin.removeFirst()

        // ── 基线 = 平滑跟踪"原始近窗高分位"(睁眼水平真相源)──────────────────────────────
        // (2026-07-30 真机 DriftDiag 定性后彻底重设计。历史失效链:baseline 只由"状态机判为睁眼的帧"
        //  决定,而一旦初始学高→睁眼被误判闭眼→采样窗冻结成旧高值→baseline 永不降→死锁。根因是把
        //  "采样"耦合到了会出错的"inBlink 判定"上。)
        // 新真相源 openLevel = rawWin 的 RAW_OPEN_Q(0.65)分位:一段时间里睁眼帧占多数且更高,高分位
        // 稳落在睁眼区;眨眼谷/短闭是少数低值落在低分位,不污染它。故它既不被单次眨眼带偏、又能如实
        // 反映"睁眼水平整体下移"(距离/角度/放松)。baseline 朝它【双向】平滑收敛:
        //   ▶ 上抬快(*0.20):睁大眼/环境变亮,迅速跟上,不迟钝。
        //   ▶ 下拉慢(*0.05):睁眼水平下移时稳步追下去,又慢到:
        //       · 用户【长时间闭眼】——闭眼帧变多会压低高分位,但下拉慢 + minEnter=0.18 硬地板,
        //         且一睁眼 openLevel 立刻回升、baseline 快速抬回,残留误判极短;
        //       · 单次眨眼/短闭——占比小,高分位几乎不动,baseline 稳如泰山。
        // 需要足够样本(≥BASE_MIN_SAMPLES)高分位才有统计意义;不足时退化为逐帧轻跟(冷启动)。
        if (rawWin.size >= BASE_MIN_SAMPLES) {
            val openLevel = quantile(rawWin.map { it.ear }, RAW_OPEN_Q)
            if (openLevel != null) {
                lastOpenLevel = openLevel   // 缓存供 hold 门用(绝对闭眼判据)——始终更新,与是否连眨串无关
                // 连眨串活跃期间【禁止基线下拉】(2026-07-30 真机 DriftDiag 定性,治"眨3记1/2"的真根因):
                // rawWin 窗(3000ms)在一串快眨里混进大量眨眼谷帧,高分位 openLevel 被这些低值拽低到
                // 【真实睁眼水平以下】(实测 base 从 ~0.19 塌到 0.115~0.125,低于用户睁眼 ear≈0.17-0.19)。
                // 基线一塌,后续每下的 drop=(base-ear)/base 就算不到 dynEnter(0.18)——第 2/3 下明明眨了却
                // 进不了 ENTER(实测 drop 仅 0.08),整下漏掉 → "眨3记1/2"。这与提帧率无关(帧其实采到了,
                // 只是判定线因分母塌陷而够不着),提帧反而让窗内谷帧更多、塌得更狠。
                // 与 line 382 的 drift 暂停同理同源:连眨是【间歇】低 EAR,openLevel 一时被污染不代表睁眼水平
                // 真的下移。故串活跃期只允许【上抬】(睁大眼无害且罕见),【冻结下拉】,不让谷帧把基线拖垮。
                // 串结束(安静 >STREAK_GAP_MS)后 rawWin 被睁眼帧重新填满、up-pull *0.20 快速抬回,残留极短。
                // sink(睁眼水平真下移)全程无连眨串 → inStreakBase=false → 照常下拉追降,回归不受影响。
                val inStreakBase = streakLastTs > 0 && (now - streakLastTs) < STREAK_GAP_MS
                if (openLevel > baseline) baseline += (openLevel - baseline) * 0.20
                else if (!inStreakBase) baseline += (openLevel - baseline) * 0.05
            }
        } else if (ear > baseline) baseline += (ear - baseline) * 0.20

        val dropNow = if (baseline > 0) (baseline - ear) / baseline else 0.0

        // 噪声上沿:仍只在"状态机认可的睁眼帧"上学(它要的是睁眼微抖幅度,故 !inBlink && drop 极小是对的)。
        if (!inBlink && dropNow < dynExit * 0.7) {
            noiseWin.addLast(DropSample(now, max(0.0, dropNow)))
            while (noiseWin.isNotEmpty() && now - noiseWin.first().t > BASE_WIN_MS) noiseWin.removeFirst()
        }

        if (rawWin.size >= BASE_MIN_SAMPLES) {
            if (noiseWin.size >= BASE_MIN_SAMPLES) {
                val nz = quantile(noiseWin.map { it.drop }, 0.90)
                if (nz != null) noiseUp += (nz - noiseUp) * 0.1
            }
            recomputeLines()
            if (!ready) {
                ready = true
                readyTs = now                 // 记安置期起点,用于"上一个"武装的启动防误触
                lastStableOpenTs = 0.0         // 睁眼稳定计时从就绪后重新起算
                listener.onReady()
            }
        }

        // 漂移自愈:仅针对"持续"的低 EAR(真环境突变,如逆光/大幅移动)。
        // 连眨是"间歇"低 EAR(眨→睁→眨),不该触发。连眨串活跃期间(距上一下 <STREAK_GAP_MS)
        // 暂停漂移计时,避免把一串快速眨眼误判成环境突变(实测每组眨完都误触发重校准)。
        val inStreakNow = streakLastTs > 0 && (now - streakLastTs) < STREAK_GAP_MS
        // drift 触发判据(2026-07-30 真机 DriftDiag 实测重设计):
        // 旧判据 `dropNow > dynExit` 错在两点,导致正常看视频每秒误触发重校准(实测连续 15s 每秒一条):
        //   (1) 门槛太低:dynExit 是"退出闭眼"线(实测钉在 0.099),用户放松看视频时自然眯眼/半闭/
        //       小眨让 ear 长期低于 base 20~50%,dropNow(0.3~0.65)轻松越过 0.099 → 每 900ms 触发。
        //       但基线其实完全健康(base=openMed=0.37 十几秒不动),根本不是环境突变。
        //   (2) 没排除 inBlink:一次正常"闭眼保持"(闭 550ms)本就是持续低 EAR,却被算进漂移。
        // 新判据:真漂移 = "状态机认为不在一次闭眼合内(!inBlink),却仍持续检测到【闭眼级】低 EAR"。
        //   - 用 dynEnter(进入线)而非 dynExit:只有达到"够得上闭眼"的显著低 EAR 才算漂移候选,
        //     自然眯眼(drop 低于进入线)直接排除。
        //   - !inBlink:正常眨眼/保持都在 inBlink 内,天然不计漂移;只有"睁眼态 EAR 却持续塌到闭眼级"
        //     (真基线学歪 / 逆光把睁眼都压下去)才是异常。
        //   - 旁路 stuckLong:真突变会让状态机困在 inBlink 出不来(ear 持续极低),这种"卡死的长闭"
        //     (dur > DRIFT_STUCK_MS=2000)仍允许自愈,不被 !inBlink 挡死。
        val stuckLong = inBlink && (now - blinkStartTs) > DRIFT_STUCK_MS
        val driftCandidate = dropNow > dynEnter && (!inBlink || stuckLong)
        if (driftCandidate && !inStreakNow) {
            if (driftSince == 0.0) driftSince = now
            else if (now - driftSince > DRIFT_HEAL_MS) {
                // 重校准目标基线:取原始近窗高分位(睁眼水平真相源),没有再退化到当前帧 ear。
                // 改用 rawWin(不受 inBlink 门控)而非旧 baseWin:后者在死锁期冻结成旧高值,重校准
                // 会把 baseline 一次次拽回旧高位、与逐帧下降打架(实测 sink 注入:base 卡 0.294 不降)。
                // 现在 baseline 平时已能靠高分位自己追降,drift 只兜底"真突变/卡死长闭"这种罕见骤变。
                val openLevel = if (rawWin.size >= BASE_MIN_SAMPLES)
                    quantile(rawWin.map { it.ear }, RAW_OPEN_Q) else null
                if (BuildConfig.DEBUG) android.util.Log.i("DriftDiag",
                    "RECAL ear=${"%.3f".format(ear)} base=${"%.3f".format(baseline)} " +
                    "dropNow=${"%.3f".format(dropNow)} dynEnter=${"%.3f".format(dynEnter)} " +
                    "inBlink=$inBlink stuckLong=$stuckLong " +
                    "openLvl=${openLevel?.let { "%.3f".format(it) } ?: "null"} rawWin=${rawWin.size} noiseUp=${"%.3f".format(noiseUp)}")
                baseline = openLevel ?: ear
                noiseUp = max(0.04, dropNow * 0.3)
                driftSince = 0.0; inBlink = false
                holdFired = false; listener.onHoldProgress(0f)
                blinkGroup.clear(); groupDeadline = 0L; groupStartTs = 0L; listener.onCount(0)
                streakFirstPeak = 0.0; streakLastTs = 0.0
                // 重校准后重新起算安置期与睁眼稳定计时:刚重校准时若正闭着眼,
                // 不许这次闭眼立刻发"上一个"(与启动同理)。
                readyTs = now; lastStableOpenTs = 0.0; prevArmed = false
                relaxUntil = now + RELAX_MS
                recomputeLines()
                listener.onRecalibrated()
            }
        } else driftSince = 0.0
    }

    private fun recomputeLines() {
        val floor = noiseUp * 1.6 + 0.03
        val target = noiseUp + (peakDrop - noiseUp) * (0.45 + bias)
        var enter = max(floor, target)
        enter = min(maxEnter, max(minEnter, enter))
        dynEnter = enter
        dynExit = max(noiseUp * 1.2, enter * 0.55)
    }

    // ---- 一次真眨眼:更新连眨串 + AGC 峰值 + 计数 ----
    // now = 本次闭眼【结束】时刻(驱动 cooldown/收尾窗口);startTs = 本次闭眼【起点】时刻(组内去重基准)。
    private fun registerBlink(now: Long, startTs: Long, peak: Double) {
        val t = now.toDouble()
        if (t - streakLastTs > STREAK_GAP_MS || streakFirstPeak == 0.0) streakFirstPeak = peak
        else streakFirstPeak = max(streakFirstPeak, peak)
        streakLastTs = t

        peakDrop += (streakFirstPeak - peakDrop) * 0.20
        peakDrop = min(0.9, max(0.12, peakDrop))
        recomputeLines()

        onBlink(now, startTs)
    }

    private fun onBlink(now: Long, startTs: Long) {
        if (now - lastTriggerTs < cooldown) return
        // 组内去重必须按【起点】间隔,不是结束点间隔(旧 bug 根因):
        // 真人快连眨每下很短(闭眼 dur 可低到 140ms),两下【结束点】会挨得很近(实测 168ms),
        // 但两下【起点】间隔是完整眨眼周期(≥ ~200ms)——中间眼睛确实重新睁开过。
        // 旧代码存/比结束点(now),把"起点分得很开、只是各自很短"的真两下误判成抖动切分而丢弃第 3 下,
        // 于是"眨 3 下只显示 2 下 / 判断成一个"。改存/比【起点】:
        //   - 真两下:起点隔 ≥200ms > 阈值 → 都计数 ✅
        //   - 一下被 rebounded 谷底抖动切成两段:第二段"起点"紧贴第一段(~40~120ms)< 阈值 → 丢弃 ✅
        // 阈值 130ms:低于真人最快自主眨眼周期(~200ms)的安全下界,高于抖动切分的伪起点间隔。
        val lastStart = blinkGroup.lastOrNull()
        if (lastStart != null && startTs - lastStart < minGapInGroup) return
        blinkGroup.add(startTs)
        listener.onCount(blinkGroup.size)
        maxBlinkStreak = max(maxBlinkStreak, blinkGroup.size)   // 纯观测:记本会话最长连眨,判定不读它
        // 即时触发:一到 nextN 下就立刻收尾发 NEXT,不再等 window 超时。
        // 这消除了"眨完第3下到真正下滑之间的那段等待"(真人反馈"感觉有点长")——
        // 那段延迟本质是 finalizeGroup 的 window 收尾窗口。达阈值即发,无尾等。
        // 因为是"到 nextN 就发",天然不受连眨相邻间隔长短影响,不存在 window 太短截断真连眨的问题。
        // 多眨的第 4/5 下落在随后的 cooldown 里被 onBlink 开头挡掉,不会二次触发、更不会翻反。
        if (blinkGroup.size >= nextN) { finalizeGroup(); return }
        // 组从空变非空这一下,记墙钟起点(任务#44:绝对寿命封顶的基准)。
        if (blinkGroup.size == 1) groupStartTs = now
        groupDeadline = now + window
    }

    private fun finalizeGroup() {
        val n = blinkGroup.size
        blinkGroup.clear(); groupDeadline = 0L; groupStartTs = 0L
        // 阈值判定:一组快速连眨达到 nextN 下即"下一个"。用 ">=" 而非精确相等——
        // 多眨一两下仍是"下一个",绝不翻成反方向(旧的相邻计数 nextN/prevN 陷阱已废)。
        // "上一个"不在这里,由闭眼保持路径触发。
        if (n >= nextN) {
            // 命中即发。注意:【不】在这里 onCount(0)——即时触发时,第 nextN 下的 onCount(n)
            // 与这里的清零几乎同帧到达 UI,会把刚点亮的第 3 个点瞬间抹掉(用户只看到 2 个亮点)。
            // 交给命中反馈(onNext→flash)统一收尾:先让计数点满亮一下,再由 flash 恢复清零。
            listener.onNext(); lastTriggerTs = maxTs()
        } else {
            // 没够数、超时清零:这里没有命中反馈接手,需自己清零点。
            listener.onCount(0)
        }
    }

    // finalizeGroup 在 onFrame 中"到期"时被调,用最近帧时间作为冷却起点(冷却仅防抖,近似足够)。
    private var lastFrameTs = 0L
    private fun maxTs(): Long = lastFrameTs

    private fun median(arr: List<Double>): Double? {
        if (arr.isEmpty()) return null
        val s = arr.sorted()
        return s[s.size / 2]
    }

    private fun quantile(arr: List<Double>, q: Double): Double? {
        if (arr.isEmpty()) return null
        val s = arr.sorted()
        val i = min(s.size - 1, max(0, (q * (s.size - 1)).toInt()))
        return s[i]
    }

    // ---- 只读诊断暴露(供 Diagnostics 组反馈快照)----
    // 全是【只读 getter / 纯观测计数器】,不加 setter、不进任何判定分支,绝不改检测行为。
    // maxBlinkStreak:本会话观测到的"单组最长连眨下数"(纯统计,判定不读它)。
    private var maxBlinkStreak = 0
    fun diagReady(): Boolean = ready
    fun diagBaseline(): Double = baseline
    fun diagOpenLevel(): Double = lastOpenLevel
    fun diagNoiseUp(): Double = noiseUp
    fun diagPeakDrop(): Double = peakDrop
    fun diagDynEnter(): Double = dynEnter
    fun diagDynExit(): Double = dynExit
    fun diagMaxStreak(): Int = maxBlinkStreak
}
