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
    private const val SCHEMA = 3
    const val HOLD_DEFAULT = 550L
    private const val HOLD_OLD_DEFAULT = 800L

    fun nextN(ctx: Context): Int { migrate(ctx); return sp(ctx).getInt(K_NEXT, 3) }
    fun holdMs(ctx: Context): Long { migrate(ctx); return sp(ctx).getLong(K_HOLD, HOLD_DEFAULT) }
    fun sens(ctx: Context): String = sp(ctx).getString(K_SENS, "auto") ?: "auto"

    fun setNextN(ctx: Context, v: Int) = sp(ctx).edit().putInt(K_NEXT, v).apply()
    fun setHoldMs(ctx: Context, v: Long) = sp(ctx).edit().putLong(K_HOLD, v).apply()
    fun setSens(ctx: Context, v: String) = sp(ctx).edit().putString(K_SENS, v).apply()

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
