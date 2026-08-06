package com.eyecontrol.app

import android.content.Context

/**
 * 用户设置持久化。全自动为默认,设置仅少量可选微调。
 *
 * 手势模型(schema v3):
 *  - 下一个 = 快速连眨 ≥ nextN 下(默认 3,阈值,多眨不翻反)。
 *  - 上一个 = 闭眼保持 ≥ holdMs 毫秒(默认 550,另一条动作轴;真人反馈 800 太慢已下调)。
 * 旧模型(v1)"上一个 = 连眨 prevN(默认 4)下"因相邻计数会被杂散眨眼翻反已废弃。
 * 见 migrate():v2 把 PREV 退役为闭眼保持;v3 把 800ms 默认下调到更跟手的 550ms。
 */
object Prefs {
    private const val FILE = "eyecontrol"
    private const val K_NEXT = "nextN"
    private const val K_PREV = "prevN"    // 已退役:仅 migrate() 读一次老值
    private const val K_HOLD = "holdMs"
    private const val K_SENS = "sens"     // "auto" | "low" | "high"
    private const val K_SCHEMA = "schemaV"
    private const val K_UPD_DAY = "lastUpdateCheckDay"   // 启动自动检查每天最多一次的日期戳
    private const val K_CONSENT = "consent"              // 匿名统计同意门:""=首启未问 / "granted" / "declined"
    private const val K_DID = "did"                      // 匿名安装标识,"u_"+UUID;仅同意后生成
    private const val K_TRACK_DAY = "lastTrackDay"       // 埋点每设备每天最多上报一次的日期戳
    private const val K_FBID = "fbid"                    // 反馈标识,"f_"+UUID;独立于埋点 did,仅点发送时懒生成
    private const val K_FB_DELIVERED = "fbDelivered"     // 已送达反馈表,多行 "fid|ts",最新在前,上限 FB_KEEP 条
    private const val FB_KEEP = 20
    private const val SCHEMA = 3
    const val HOLD_DEFAULT = 550L
    private const val HOLD_OLD_DEFAULT = 800L

    fun nextN(ctx: Context): Int { migrate(ctx); return sp(ctx).getInt(K_NEXT, 3) }
    fun holdMs(ctx: Context): Long { migrate(ctx); return sp(ctx).getLong(K_HOLD, HOLD_DEFAULT) }
    fun sens(ctx: Context): String = sp(ctx).getString(K_SENS, "auto") ?: "auto"

    fun setNextN(ctx: Context, v: Int) = sp(ctx).edit().putInt(K_NEXT, v).apply()
    fun setHoldMs(ctx: Context, v: Long) = sp(ctx).edit().putLong(K_HOLD, v).apply()
    fun setSens(ctx: Context, v: String) = sp(ctx).edit().putString(K_SENS, v).apply()

    /** 启动自动检查更新的节流:上次检查日期(yyyy-MM-dd,本地时区),空串=从未检查。 */
    fun lastUpdateCheckDay(ctx: Context): String = sp(ctx).getString(K_UPD_DAY, "") ?: ""
    fun setLastUpdateCheckDay(ctx: Context, day: String) =
        sp(ctx).edit().putString(K_UPD_DAY, day).apply()

    // ---- 匿名活跃统计(同意门 + 每设备每天一次节流)----
    //  隐私红线:未同意 = 零采集(不生成 did、不上报)。同意后才生成安装级随机标识。
    //  did 是随机 UUID,不含任何设备真实身份;卸载重装会变(看板脚注已注明活跃偏高)。

    /** 同意态:""=首启尚未询问(应弹同意门) / "granted"=已同意 / "declined"=已拒绝。 */
    fun consent(ctx: Context): String = sp(ctx).getString(K_CONSENT, "") ?: ""
    fun setConsent(ctx: Context, v: String) = sp(ctx).edit().putString(K_CONSENT, v).apply()

    /**
     * 取匿名安装标识,没有则生成并持久化。格式 "u_"+UUID(仅十六进制与连字符)。
     * 【只应在 consent=="granted" 后调用】——未同意绝不生成,保证"零采集"。
     */
    fun getOrCreateDid(ctx: Context): String {
        val p = sp(ctx)
        val cur = p.getString(K_DID, "") ?: ""
        if (cur.isNotEmpty()) return cur
        val did = "u_" + java.util.UUID.randomUUID().toString()
        p.edit().putString(K_DID, did).apply()
        return did
    }

    /** 埋点节流:上次上报日期(yyyy-MM-dd),空串=今天尚未报。每设备每天只上报一次。 */
    fun lastTrackDay(ctx: Context): String = sp(ctx).getString(K_TRACK_DAY, "") ?: ""
    fun setLastTrackDay(ctx: Context, day: String) =
        sp(ctx).edit().putString(K_TRACK_DAY, day).apply()

    // ---- 用户主动反馈(独立于埋点同意门)----
    //  反馈是用户【主动知情】的行为(自己点发送),与被动埋点的同意门是两回事:
    //  未同意匿名统计的用户【也能反馈】。因此反馈标识 fbid 独立于埋点 did,
    //  绝不复用 getOrCreateDid(那只在 granted 后调),以免"一反馈就生成 did"破坏零采集红线。

    /**
     * 取反馈标识,没有则生成并持久化。格式 "f_"+UUID。
     * 【仅在用户点发送那一刻按需调用】——只点开对话框不创建;用户点发送 = 对该条反馈的知情同意。
     */
    fun getOrCreateFeedbackId(ctx: Context): String {
        val p = sp(ctx)
        val cur = p.getString(K_FBID, "") ?: ""
        if (cur.isNotEmpty()) return cur
        val fbid = "f_" + java.util.UUID.randomUUID().toString()
        p.edit().putString(K_FBID, fbid).apply()
        return fbid
    }

    /** 已生成的反馈标识;从未反馈过则空串(不触发生成)。 */
    fun feedbackId(ctx: Context): String = sp(ctx).getString(K_FBID, "") ?: ""

    /**
     * 记一条反馈已送达(云端 201 或"文件已存在"幂等)。fid 唯一,重复记入不叠加。
     * 存最近 FB_KEEP 条 "fid|ts",最新在前;供"我的反馈"列表展示编号。
     */
    fun markFeedbackDelivered(ctx: Context, fid: String, ts: Long) {
        val p = sp(ctx)
        val kept = deliveredFeedback(ctx).filter { it.first != fid }   // 去掉同 fid 旧记录(幂等)
        val merged = (listOf(fid to ts) + kept).take(FB_KEEP)
        val text = merged.joinToString("\n") { "${it.first}|${it.second}" }
        p.edit().putString(K_FB_DELIVERED, text).apply()
    }

    /** 已送达反馈列表:[(fid, ts)],最新在前。 */
    fun deliveredFeedback(ctx: Context): List<Pair<String, Long>> {
        val raw = sp(ctx).getString(K_FB_DELIVERED, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val i = line.indexOf('|')
            if (i <= 0) return@mapNotNull null
            val fid = line.substring(0, i)
            val ts = line.substring(i + 1).toLongOrNull() ?: return@mapNotNull null
            fid to ts
        }
    }

    /**
     * 一次性迁移。
     * v2:把旧的"上一个=连眨 prevN 下"彻底退役(相邻计数会被杂散眨眼翻反),PREV 改为闭眼保持。
     * v3:真人反馈 800ms 保持太慢 → 下调默认到 550ms。仅当当前值仍是老默认 800(或更大、
     *     即没被用户主动调快过)时才改写为 550,保留用户手动设过的更快值。
     * nextN:老默认 3 == 新默认 3,保留;用户若改过则 clamp 到 2..6 沿用其偏好。
     * 保证没有任何老装机被搁在"多眨翻反"旧映射或"保持太慢"的旧默认上。
     */
    private fun migrate(ctx: Context) {
        val p = sp(ctx)
        val from = p.getInt(K_SCHEMA, 1)
        if (from >= SCHEMA) return
        val e = p.edit()
        // --- v1 → v2 ---
        if (from < 2) {
            if (p.contains(K_NEXT)) e.putInt(K_NEXT, p.getInt(K_NEXT, 3).coerceIn(2, 6))
            e.remove(K_PREV)                 // 退役旧的按次"上一个"
            if (!p.contains(K_HOLD)) e.putLong(K_HOLD, HOLD_OLD_DEFAULT)
        }
        // --- (v1/v2) → v3:下调保持默认 ---
        // 没显式存过 holdMs,或仍停在 800ms(旧默认、非主动调快)→ 用新默认 550。
        val cur = p.getLong(K_HOLD, HOLD_OLD_DEFAULT)
        if (!p.contains(K_HOLD) || cur >= HOLD_OLD_DEFAULT) e.putLong(K_HOLD, HOLD_DEFAULT)
        e.putInt(K_SCHEMA, SCHEMA)
        e.apply()
    }

    /** 灵敏度档 → BlinkDetector.bias */
    fun biasOf(sens: String): Double = when (sens) {
        "low" -> 0.06
        "high" -> -0.06
        else -> 0.0
    }

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
