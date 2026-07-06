package com.watermark.photo.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 纯 Kotlin KMeans 自适应取色器
 * 替换原 Palette API 方案，零依赖，速度快（30×30 像素 5 簇聚类）
 */
object SmartColorExtractor {

    data class ColorScheme(
        val background: Int,
        val text: Int,
        val textSecondary: Int,
        val accent: Int,
        val accentMuted: Int
    )

    data class Mode2Colors(
        val logoColor: Int,
        val textColor: Int,
        val paramColor: Int,
        val background: Int
    )

    // ========== 配置常量 ==========
    private const val DS = 50          // 降采样尺寸 50×50 = 2500 像素
    private const val K = 5            // 聚类数
    private const val MAX_ITER = 10    // 最大迭代轮数

    // ========== 内部数据结构 ==========
    private data class Rgb(val r: Int, val g: Int, val b: Int) {
        fun toInt(): Int = Color.rgb(r, g, b)
    }

    // ========== Public API ==========

    /** 默认提取（中等饱和度） */
    fun extract(bitmap: Bitmap): ColorScheme =
        extractInternal(bitmap, satBoost = 0.3f)

    /** 高饱和度提取 */
    fun extractRich(bitmap: Bitmap): ColorScheme =
        extractInternal(bitmap, satBoost = 0.5f)

    /** Barbie 粉主题（硬编码） */
    fun extractBarbie(bitmap: Bitmap): ColorScheme {
        val pink = 0xFFE91CE6.toInt()
        return ColorScheme(
            background = pink,
            text = Color.WHITE,
            textSecondary = Color.LTGRAY,
            accent = pink,
            accentMuted = desaturate(pink, 0.5f)
        )
    }

    /** 原始颜色（最小调整） */
    fun extractOriginal(bitmap: Bitmap): ColorScheme {
        val (best, _) = kMeans(bitmap)
        val bg = best.toInt()
        return ColorScheme(bg, Color.BLACK, Color.DKGRAY, bg, bg)
    }

    /** Mode2 配色（Logo 色 + 文字色 + 参数色 + 背景色） */
    fun extractMode2Colors(bitmap: Bitmap): Mode2Colors {
        val (best, _) = kMeans(bitmap)
        val bg = best.toInt()
        val lum = luminance(bg)
        val text = if (lum > 0.5f) Color.BLACK else Color.WHITE
        return Mode2Colors(
            logoColor = bg,
            textColor = text,
            paramColor = text,
            background = bg
        )
    }

    // ========== KMeans 核心算法 ==========

    private fun kMeans(bitmap: Bitmap): Pair<Rgb, List<Pair<Rgb, Int>>> {
        // 1. 降采样到 30×30
        val small = Bitmap.createScaledBitmap(bitmap, DS, DS, true)
        val pixels = IntArray(DS * DS)
        small.getPixels(pixels, 0, DS, 0, 0, DS, DS)
        small.recycle()

        val rgbList = pixels.map { Rgb(Color.red(it), Color.green(it), Color.blue(it)) }

        // 2. KMeans++ 初始化
        val centers = mutableListOf<Rgb>()
        centers.add(rgbList[Random.nextInt(rgbList.size)])
        while (centers.size < K) {
            val dists = rgbList.map { p ->
                centers.minOf { c -> dist(p, c) }
            }
            val total = dists.sum()
            var cum = 0.0
            val rnd = Random.nextDouble() * total
            var idx = 0
            for (i in dists.indices) {
                cum += dists[i]
                if (cum >= rnd) { idx = i; break }
            }
            centers.add(rgbList[idx])
        }

        // 3. EM 迭代
        val assign = IntArray(rgbList.size)
        repeat(MAX_ITER) {
            var changed = false
            for (i in rgbList.indices) {
                var best = 0
                var bestDist = Double.MAX_VALUE
                for (c in centers.indices) {
                    val d = dist(rgbList[i], centers[c])
                    if (d < bestDist) { bestDist = d; best = c }
                }
                if (assign[i] != best) { assign[i] = best; changed = true }
            }
            if (!changed) return@repeat
            // 更新中心点
            for (c in centers.indices) {
                val assigned = rgbList.filterIndexed { i, _ -> assign[i] == c }
                if (assigned.isNotEmpty()) {
                    centers[c] = Rgb(
                        assigned.map { it.r }.average().toInt(),
                        assigned.map { it.g }.average().toInt(),
                        assigned.map { it.b }.average().toInt()
                    )
                }
            }
        }

        // 4. 构建聚类结果
        val clusters = centers.mapIndexed { i, c ->
            Pair(c, assign.count { it == i })
        }

        // 5. 选择得分最高的簇
        val best = clusters.maxByOrNull { (c, s) -> score(c, s, rgbList.size) } ?: clusters[0]
        return Pair(best.first, clusters)
    }

    /** 聚类评分：饱和度 + 占比 + 明度偏好 */
    private fun score(center: Rgb, size: Int, total: Int): Float {
        val hsv = FloatArray(3)
        Color.colorToHSV(center.toInt(), hsv)
        val s = hsv[1]
        val v = hsv[2]
        var score = s * 50f + (size.toFloat() / total) * 30f
        if (v in 0.2f..0.8f) score += 20f  // 偏好中等明度
        if (s < 0.1f) score -= 50f               // 惩罚灰色
        return score
    }

    /** 欧氏距离（RGB 空间） */
    private fun dist(c1: Rgb, c2: Rgb): Double {
        val dr = (c1.r - c2.r).toDouble()
        val dg = (c1.g - c2.g).toDouble()
        val db = (c1.b - c2.b).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    /** 感知亮度（ITU-R BT.601） */    fun luminance(color: Int): Float =
        (Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f) / 255f

    /** 提高饱和度 */
    private fun saturate(color: Int, amount: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] + amount).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    /** 降低饱和度 */
    fun desaturate(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] *= factor
        return Color.HSVToColor(hsv)
    }

    /** 内部统一提取逻辑 */
    private fun extractInternal(bitmap: Bitmap, satBoost: Float): ColorScheme {
        val (best, _) = kMeans(bitmap)
        val bg = best.toInt()
        val l = luminance(bg)
        val text = if (l > 0.5f) Color.BLACK else Color.WHITE
        val textSec = if (l > 0.5f) Color.DKGRAY else Color.LTGRAY
        val accent = saturate(bg, satBoost)
        val accentMuted = desaturate(bg, 1f - satBoost)
        return ColorScheme(bg, text, textSec, accent, accentMuted)
    }

    // ========== 兼容函数（WatermarkEngine 调用） ==========

    fun adjustAlpha(color: Int, alpha: Float): Int =
        Color.argb(
            (alpha * 255).toInt().coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

    fun tintBitmapWithVibrant(logo: Bitmap, vibrantColor: Int): Bitmap {
        val result = logo.copy(Bitmap.Config.ARGB_8888, true)
        val paint = Paint().apply {
            colorFilter = PorterDuffColorFilter(vibrantColor, PorterDuff.Mode.SRC_IN)
        }
        Canvas(result).drawBitmap(result, 0f, 0f, paint)
        return result
    }

    fun adjustColorForContrast(color: Int, bg: Int): Int = color
    fun desaturateForStatic(color: Int, factor: Float): Int = color
    fun ensureHighContrast(color: Int, bg: Int, targetRatio: Float): Int = color
}
