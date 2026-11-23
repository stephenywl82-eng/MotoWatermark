package com.watermark.photo.core

import android.content.Context
import android.graphics.*
import com.watermark.photo.R
import com.watermark.photo.data.SmartColorExtractor
import com.watermark.photo.data.WatermarkInfo
import kotlin.math.abs
import kotlin.math.max as kMax
import kotlin.math.min as kMin

/**
 * Motorola 水印引擎
 */
object WatermarkEngine {

    // v1.3.7 [原版对齐]: 参数文字统一比例——横竖屏一致，消除横屏偏大/竖屏偏小。
    // 真机逐像素实测(同图同参数对比原版)：
    // 原版参数 墨迹高=22px(0.155barH)、文字宽=306px、笔画宽≈7px(粗体)；
    // v1.4.1: 用系统真粗体 SANS_SERIF BOLD(笔画够粗) + textSize=0.167barH(墨迹高≈22)
    //         + textScaleX=0.94(字宽压到≈306)，兼顾粗细与宽度。
    private const val PARAM_TEXT_RATIO = 0.167f

    private var montserratTypeface: Typeface? = null

    // ==================== 辅助方法 ====================

    /** 颜色淡化：向白色混合 fadeRatio（0~1），比直接调 alpha 更稳定 */
    private fun fadeColor(color: Int, fadeRatio: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * fadeRatio).toInt()
        val g = (Color.green(color) + (255 - Color.green(color)) * fadeRatio).toInt()
        val b = (Color.blue(color) + (255 - Color.blue(color)) * fadeRatio).toInt()
        return Color.rgb(r, g, b)
    }

    private fun computeLogoColor(bgColor: Int): Int {
        val lum = (((Color.red(bgColor) * 0.299f) + (Color.green(bgColor) * 0.587f)) + (Color.blue(bgColor) * 0.114f)) / 255.0f
        return if (lum > 0.5f) Color.rgb(26, 26, 26) else Color.rgb(245, 245, 245)
    }

    /**
     * v1.1.8: Signature 机型——机型名位置改用花体 logo 绘制，替代普通文字。
     * @param centerY 花体垂直中心
     * @param targetH 花体绘制高度（由调用方按底栏高度决定，保证够大且不溢出）
     * @return 是否命中并绘制了花体（true=已绘制，调用方应跳过原文字）
     */
    private fun drawSignatureIfNeeded(
        canvas: Canvas, deviceName: String, context: Context,
        anchorX: Float, centerY: Float, targetH: Float,
        color: Int, align: Paint.Align
    ): Boolean {
        if (!deviceName.contains("signature", ignoreCase = true)) return false
        val sig = try {
            BitmapFactory.decodeResource(context.resources, R.drawable.signature_script)
        } catch (e: Exception) { null } ?: return false

        val ratio = sig.width.toFloat() / sig.height
        val targetW = targetH * ratio
        val left = when (align) {
            Paint.Align.RIGHT -> anchorX - targetW
            Paint.Align.CENTER -> anchorX - targetW / 2f
            else -> anchorX
        }
        // v1.2.9: 花体按"视觉主体带中心"对齐，而非图片几何中心。
        // signature_script.png 因字母 g 下垂，图片几何中心 y=162.5 比笔画主体带中心 y=149 低 13.5px(4.15%)；
        // 只有用主体带中心对齐，signature 才与左侧 motorola 字标处于同一水平线（对齐官方参考图）。
        val top = centerY - (149f / 325f) * targetH
        val tinted = tintBitmap(sig, color)
        canvas.drawBitmap(tinted, null, RectF(left, top, left + targetW, top + targetH), null)
        tinted.recycle()
        sig.recycle()
        return true
    }

    /**
     * 深色文字模式下翻转 FIFA logo：白色区域→黑，黑色文字→白，金色保留原色
     */
    private fun invertRightLogoForDark(logo: Bitmap): Bitmap {
        val pixels = IntArray(logo.width * logo.height)
        logo.getPixels(pixels, 0, logo.width, 0, 0, logo.width, logo.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p shr 24) and 0xFF
            if (a < 128) continue  // 透明像素不动
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            pixels[i] = when {
                r > 240 && g > 240 && b > 240 -> (a shl 24) or (0x00 shl 16) or (0x00 shl 8) or 0x00   // 白色 → 纯黑
                r < 40 && g < 40 && b < 40    -> (a shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0xFF   // 暗色 → 纯白
                else                          -> p                                                     // 金色/其他 → 保留
            }
        }
        val result = Bitmap.createBitmap(logo.width, logo.height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, logo.width, 0, 0, logo.width, logo.height)
        return result
    }

    private fun tintBitmap(source: Bitmap, color: Int): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    /** v1.2.4: 反色双色 logo（黑圆白M ↔ 白圆黑M），保留 alpha 与明暗层次。
     *  invert=true 时黑→白、白→黑；false 返回原图副本。*/
    private fun invertLogoColors(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(source.width * source.height)
        source.getPixels(px, 0, source.width, 0, 0, source.width, source.height)
        for (i in px.indices) {
            val a = Color.alpha(px[i])
            if (a == 0) continue
            val r = 255 - Color.red(px[i])
            val g = 255 - Color.green(px[i])
            val b = 255 - Color.blue(px[i])
            px[i] = Color.argb(a, r, g, b)
        }
        out.setPixels(px, 0, source.width, 0, 0, source.width, source.height)
        return out
    }

    private fun getMontserratTypeface(context: Context): Typeface {
        if (montserratTypeface == null) {
            synchronized(this) {
                if (montserratTypeface == null) {
                    montserratTypeface = Typeface.createFromAsset(context.assets, "Montserrat-Regular.ttf")
                }
            }
        }
        return montserratTypeface!!
    }

    /** v1.1.4：计算让 namePaint 的文字与其上方 "@" 视觉居中所需的 baseline 偏移。
     *  以真实墨迹框 getTextBounds 中心为基准，含降部字母/纯大写/中文名字都能对齐。 */
    private fun nameBaselineOffset(atPaint: Paint, namePaint: Paint, name: String): Float {
        val rb = Rect()
        atPaint.getTextBounds("@", 0, 1, rb)
        val atCenter = (rb.top + rb.bottom) / 2f
        namePaint.getTextBounds(name, 0, name.length, rb)
        val nmCenter = (rb.top + rb.bottom) / 2f
        return atCenter - nmCenter
    }

    /** v1.3.2: 计算让文字"实际墨迹中心"精确落在 centerY 的 baseline。
     *  用 getTextBounds 的真实墨迹框（含降部/大小写），比 fontMetrics 几何中心更贴合视觉。
     *  用于普通机型文字与图片字标/圆形 logo 的水平对齐。 */
    private fun baselineForVisualCenter(paint: Paint, text: String, centerY: Float): Float {
        if (text.isEmpty()) return centerY
        val rb = Rect()
        paint.getTextBounds(text, 0, text.length, rb)
        return centerY - (rb.top + rb.bottom) / 2f
    }

    private fun buildParamString(info: WatermarkInfo): String {
        return "${info.focalLength}  ${info.aperture}  ${info.shutterSpeed}  ${info.iso}"
    }

    // ==================== Drawing Methods ====================

    private fun drawBarMode(canvas: Canvas, source: Bitmap, srcW: Int, srcH: Int, spec: WatermarkMath.LayoutSpec, info: WatermarkInfo, colorScheme: SmartColorExtractor.ColorScheme, context: Context, extend: Boolean = false) {
        val barHeight = spec.barH
        val isPortrait = srcH > srcW

        if (extend) {
            // 原图画在顶部
            canvas.drawBitmap(source, 0f, 0f, null)

            // 用原图高度计算 barTop（在扩展画布底部）
            val photoH = srcH - barHeight  // 恢复照片实际高度
            val barTop = photoH

            // 底栏背景
            val bgPaint = Paint()
            val manualBg = if (info.useWhiteBg) Color.WHITE else colorScheme.background
            bgPaint.color = manualBg
            canvas.drawRect(0f, barTop, srcW.toFloat(), srcH.toFloat(), bgPaint)

            val autoTextColor = computeLogoColor(bgPaint.color)
            val leftTextColor = when (info.useWhiteText) {
                true -> Color.WHITE
                false -> Color.BLACK
                null -> autoTextColor
            }
            val mutedColor = SmartColorExtractor.adjustAlpha(leftTextColor, 0.70f)
            drawLeftSection(canvas, barHeight * 0.25f, barTop, barHeight, info, leftTextColor, context)
            drawRightSection(canvas, srcW, barTop, barHeight, info, leftTextColor, mutedColor, context, isPortrait, info.style)
            return
        }

        canvas.drawBitmap(source, 0f, 0f, null)

        val barTop = srcH - barHeight   // 底栏顶部 Y 坐标

        // 底栏背景 - 纯色莫兰迪色
        val bgPaint = Paint()
        bgPaint.color = if (info.useWhiteBg) Color.WHITE else colorScheme.background
        canvas.drawRect(0f, barTop, srcW.toFloat(), srcH.toFloat(), bgPaint)

        // 白底时自动用黑字
        val autoTextColor = computeLogoColor(bgPaint.color)
        val leftTextColor = when (info.useWhiteText) {
            true -> Color.WHITE
            false -> Color.BLACK
            null -> autoTextColor
        }
        val mutedColor = SmartColorExtractor.adjustAlpha(leftTextColor, 0.70f)
        drawLeftSection(canvas, barHeight * 0.25f, barTop, barHeight, info, leftTextColor, context)

        // 右侧参数 + 日期 + FIFA Logo
        drawRightSection(canvas, srcW, barTop, barHeight, info, leftTextColor, mutedColor, context, isPortrait, info.style)
    }

private fun drawDiagonalMode(canvas: Canvas, source: Bitmap, srcW: Int, srcH: Int, spec: WatermarkMath.LayoutSpec, info: WatermarkInfo, colorScheme: SmartColorExtractor.ColorScheme, context: Context) {
        canvas.drawBitmap(source, 0f, 0f, null)

        // 手动取色优先，否则从照片取色
        val mode2Colors = if (info.manualPickColor != null) {
            val lum = SmartColorExtractor.luminance(info.manualPickColor)
            val text = if (lum > 0.5f) Color.BLACK else Color.WHITE
            val mutedText = SmartColorExtractor.adjustAlpha(text, 0.75f)
            SmartColorExtractor.Mode2Colors(
                logoColor = info.manualPickColor,
                textColor = text,
                paramColor = mutedText,
                background = info.manualPickColor
            )
        } else {
            SmartColorExtractor.extractMode2Colors(source)
        }

        // 左下角 Logo（带主题色）
        var logo: Bitmap? = try {
            BitmapFactory.decodeResource(context.resources, R.drawable.picsart_mode3_logo)
        } catch (e: Exception) { null }

        if (logo != null && !logo.isRecycled) {
            val tinted = SmartColorExtractor.tintBitmapWithVibrant(logo, mode2Colors.logoColor)
            val logoW = (srcW * 0.08f).toInt()
            val logoH = ((tinted.height.toFloat() / tinted.width) * logoW).toInt()
            val scaled = Bitmap.createScaledBitmap(tinted, logoW, logoH, true)
            canvas.drawBitmap(scaled, srcW * 0.03f, srcH - logoH - srcH * 0.02f, null)
            scaled.recycle()
            tinted.recycle()
            logo.recycle()
        }

        // 文字颜色（与背景最大反差）
        val logoColor = mode2Colors.logoColor
        val lum = (((Color.red(logoColor) * 0.299f) + (Color.green(logoColor) * 0.587f)) + (Color.blue(logoColor) * 0.114f)) / 255.0f
        val textColor = SmartColorExtractor.ensureHighContrast(logoColor, if (lum > 0.5f) 0xFF000000.toInt() else -1, 3.8f)

        val textSize = srcH * 0.02f
        val rightX = srcW - textSize
        val bottomY = srcH - 0.018f * srcH

        val deviceName = if (info.deviceName.lowercase().contains("motorola")) {
            info.deviceName
        } else {
            "Motorola ${info.deviceName}"
        }

        // device name (with shadow)
        val namePaint = Paint()
        namePaint.color = textColor
        namePaint.textSize = textSize
        namePaint.typeface = Typeface.create(getMontserratTypeface(context), Typeface.BOLD)
        namePaint.textAlign = Paint.Align.RIGHT
        namePaint.setShadowLayer(textSize * 0.15f, 0f, textSize * 0.08f, Color.argb(120, 0, 0, 0))
        // v1.1.8: Signature 机型用花体替代机型名文字（放大）
        // v1.2.1: 花体放大 1.63×（textSize 2.6→4.24），对齐官方 signature 视觉大小
        val sigH3 = textSize * 4.24f
        if (!drawSignatureIfNeeded(canvas, deviceName, context, rightX, bottomY - sigH3 / 2f, sigH3, textColor, Paint.Align.RIGHT)) {
            canvas.drawText(deviceName, rightX, bottomY, namePaint)
        }

        // params line (with shadow)
        if (info.showParams) {
            val paramPaint = Paint()
            paramPaint.color = SmartColorExtractor.adjustAlpha(textColor, 0.75f)
            paramPaint.textSize = 0.78f * textSize
            paramPaint.typeface = Typeface.create(getMontserratTypeface(context), Typeface.NORMAL)
            paramPaint.textAlign = Paint.Align.RIGHT
            paramPaint.setShadowLayer(textSize * 0.12f, 0f, textSize * 0.06f, Color.argb(120, 0, 0, 0))
            canvas.drawText(buildParamString(info), rightX, bottomY - 1.1f * textSize, paramPaint)
        }

        // date line (with shadow)
        if (info.showDate) {
            val datePaint = Paint()
            datePaint.color = SmartColorExtractor.adjustAlpha(textColor, 0.65f)
            datePaint.textSize = 0.72f * textSize
            datePaint.typeface = Typeface.create(getMontserratTypeface(context), Typeface.NORMAL)
            datePaint.textAlign = Paint.Align.RIGHT
            datePaint.setShadowLayer(textSize * 0.10f, 0f, textSize * 0.05f, Color.argb(120, 0, 0, 0))
            canvas.drawText(info.date, rightX, bottomY - 1.1f * textSize - textSize * 1.05f, datePaint)
        }

        // Owner name top-left
        if (info.ownerName.isNotBlank()) {
            val nameSize = srcH * 0.018f
            val atSize = nameSize * 1.25f
            val atPaint = Paint().apply {
                color = Color.argb(160, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
                this.textSize = atSize
                typeface = Typeface.create(getMontserratTypeface(context), Typeface.NORMAL)
                textAlign = Paint.Align.LEFT
            }
            val namePaint = Paint().apply {
                color = Color.argb(160, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
                this.textSize = nameSize
                typeface = Typeface.create(getMontserratTypeface(context), Typeface.NORMAL)
                textAlign = Paint.Align.LEFT
            }
            val x = srcW * 0.035f
            val y = srcH * 0.038f
            canvas.drawText("@", x, y, atPaint)
            canvas.drawText(info.ownerName, x + atPaint.measureText("@"), y + nameBaselineOffset(atPaint, namePaint, info.ownerName), namePaint)
        }
    }

    // 从 APK 资源加载缓存的 Motorola logo Bitmap
    private var motorolaLogoBitmap: Bitmap? = null

    private fun getMotorolaLogo(context: Context): Bitmap? {
        if (motorolaLogoBitmap == null || motorolaLogoBitmap?.isRecycled == true) {
            try {
                val resId = R.drawable.motorola_logo
                val drawable = context.resources.getDrawable(resId, context.theme)
                val bitmap = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val c = Canvas(bitmap)
                drawable.setBounds(0, 0, c.width, c.height)
                drawable.draw(c)
                motorolaLogoBitmap = bitmap
            } catch (e: Exception) {
                motorolaLogoBitmap = null
            }
        }
        return motorolaLogoBitmap
    }

    private fun drawLeftSection(canvas: Canvas, startX: Float, barTop: Float, barH: Float, info: WatermarkInfo, textColor: Int, context: Context) {
        // 仅 Motorola 设备才显示左侧内容
        if (!info.isMotorola) return

        val displayName = info.deviceName
        val suffix = if (displayName.lowercase().startsWith("motorola")) {
            displayName.removePrefix("motorola").removePrefix(" ").trimStart()
        } else {
            displayName
        }

        val typeface = Typeface.create(getMontserratTypeface(context), Typeface.BOLD)

        // Motorola logo 图片（着色替换文字 "motorola"）
        val logo = getMotorolaLogo(context)
        val logoStartX = startX + barH * 0.06f
        val desiredLogoH = barH * 0.257f
        val logoRatio = logo?.let { it.width.toFloat() / it.height } ?: (118f / 31f)
        val logoW = logoRatio * desiredLogoH
        // v1.2.2: motorola 字标缩小到 0.876×（maxLogoW 1.05→0.92），对齐官方纤细字标
        val maxLogoW = barH * 0.92f
        val (actualLogoW, actualLogoH) = if (logoW > maxLogoW) {
            maxLogoW to (maxLogoW / logoRatio)
        } else {
            logoW to desiredLogoH
        }

        val hasOwnerName = info.ownerName.isNotBlank()
        val nameSize = desiredLogoH * 0.60f
        val atSize = nameSize * 1.25f
        val nameGap = nameSize * 1.30f
        val groupH = if (hasOwnerName) actualLogoH + nameGap + atSize else actualLogoH
        val freeSpace = barH - groupH
        val groupTop = if (hasOwnerName) barTop + freeSpace / 2f + freeSpace * 0.20f else barTop + freeSpace / 2f
        val logoTop = groupTop

        // Draw logo only when enabled
        if (info.showLogo && logo != null && !logo.isRecycled) {
            val tinted = tintBitmap(logo, textColor)
            canvas.drawBitmap(tinted, null, RectF(logoStartX, logoTop, logoStartX + actualLogoW, logoTop + actualLogoH), null)
            tinted.recycle()
        }

        // Suffix always at same position (right of the reserved logo space)
        if (suffix.isNotEmpty()) {
            val suffixPaint = Paint().apply {
                color = textColor
                // v1.3.3: 缩小机型文字字号匹配固定的 motorola 字标（0.2406→0.20，约0.83×）
                // 水平对齐仍由 baselineForVisualCenter 按墨迹中心计算，改字号后自动保持
                textSize = 0.20f * barH
                this.typeface = typeface
                textAlign = Paint.Align.LEFT
                isFakeBoldText = false
            }
            // v1.3.6 [B方案]: 严格基线对齐——对齐到 motorola 字标的"墨迹中心"而非图片几何中心。
            // motorola_logo.png 字母偏下（升部少、主体带多），墨迹中心在图片高度 58% 处（真机实测主体带中心=图高×0.58）；
            // 旧代码用几何中心(0.5)导致机型文字/花体整体比字标高约4.5px。改为 0.58 后三者严格同一水平线。
            val suffixCenterY = logoTop + actualLogoH * 0.58f
            val suffixX = if (info.showLogo) logoStartX + actualLogoW + barH * 0.0693f else logoStartX
            // v1.2.6: Signature 花体左移贴近 motorola 字标（图片左侧有透明留白），并缩到与官方等宽
            // 花体图片自带约14px左留白，官方花体紧挨字标，故额外左移 barH*0.06；大小 3.42→3.22
            if (!drawSignatureIfNeeded(canvas, suffix, context, suffixX - barH * 0.06f, suffixCenterY, actualLogoH * 3.22f, textColor, Paint.Align.LEFT)) {
                // v1.3.2: 普通机型文字按实际墨迹中心对齐字标中心线（不再用写死 0.38×字号）
                val text = suffix.lowercase()
                canvas.drawText(text, suffixX, baselineForVisualCenter(suffixPaint, text, suffixCenterY), suffixPaint)
            }
        }

        // Owner name below logo (same position regardless of logo visibility)
        if (hasOwnerName) {
            val atPaint = Paint().apply {
                color = Color.argb(180, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
                textSize = atSize
                this.typeface = typeface
                textAlign = Paint.Align.LEFT
                isFakeBoldText = false
            }
            val namePaint = Paint().apply {
                color = Color.argb(180, Color.red(textColor), Color.green(textColor), Color.blue(textColor))
                textSize = nameSize
                this.typeface = typeface
                textAlign = Paint.Align.LEFT
                isFakeBoldText = false
            }
            val ownerY = logoTop + actualLogoH + nameGap
            val atWidth = atPaint.measureText("@")
            // [v1.1.4] 名字与 "@" 视觉居中对齐（此前偏高）
            canvas.drawText("@", logoStartX, ownerY, atPaint)
            canvas.drawText(info.ownerName, logoStartX + atWidth, ownerY + nameBaselineOffset(atPaint, namePaint, info.ownerName), namePaint)
        }
    }

    private fun drawRightSection(canvas: Canvas, srcW: Int, barTop: Float, barH: Float, info: WatermarkInfo, textColor: Int, mutedColor: Int, context: Context, isPortrait: Boolean = false, style: Int = 0) {
        if (style == 4) {
            drawMode4Right(canvas, srcW, barTop, barH, info, textColor, mutedColor, context, isPortrait)
            return
        }

        // Style 11: F1 logo (bigger), content shifted left to make room
        if (style == 11) {
            drawMode11Right(canvas, srcW, barTop, barH, info, textColor, mutedColor, context, isPortrait)
            return
        }

        var rightX = srcW - barH * 0.2f

        // Right logo: white variant on dark bg, black variant on light bg
        val textLum = (((Color.red(textColor) * 0.299f) + (Color.green(textColor) * 0.587f)) + (Color.blue(textColor) * 0.114f)) / 255.0f
        val logoResId = if (textLum >= 0.5f) R.drawable.mode1_logo_white else R.drawable.mode1_logo_black
        val logo = BitmapFactory.decodeResource(context.resources, logoResId)
        if (logo != null && !logo.isRecycled) {
            val logoH = barH * 0.68f
            val logoW = (logo.width.toFloat() / logo.height) * logoH
            val logoX = rightX - logoW
            val logoY = barTop + (barH - logoH) / 2f
            canvas.drawBitmap(logo, null, RectF(logoX, logoY, logoX + logoW, logoY + logoH), null)
            logo.recycle()
            rightX = logoX - barH * 0.2f
        }

        // params line
        if (info.showParams) {
            val paramPaint = Paint()
            paramPaint.color = textColor
            paramPaint.textSize = PARAM_TEXT_RATIO * barH
            paramPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            paramPaint.letterSpacing = 0.015f
            paramPaint.textAlign = Paint.Align.RIGHT
            val paramTextSize = paramPaint.textSize
            // Without date: vertically center in bar; with date: keep upper section
            val paramY = if (!info.showDate) barTop + barH / 2f + 0.35f * paramTextSize
                         else barTop + 0.38f * barH + 0.35f * paramTextSize
            canvas.drawText(buildParamString(info), rightX, paramY, paramPaint)
        }

        // date line — 下方 69% 位置，与参数行间距收拢；如果只有日期则垂直居中
        if (info.showDate) {
            val datePaint = Paint()
            datePaint.color = fadeColor(textColor, 0.17f)
            // v1.2.3: 日期与参数行统一粗细/字号（NORMAL→BOLD, 0.17765→0.168）
            datePaint.textSize = PARAM_TEXT_RATIO * barH
            datePaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            datePaint.letterSpacing = 0.015f
            datePaint.textAlign = Paint.Align.RIGHT
            val dateTextSize = datePaint.textSize
            val dateY = if (!info.showParams) barTop + barH / 2f + 0.38f * dateTextSize
                        else barTop + 0.69f * barH + 0.35f * dateTextSize
            canvas.drawText(info.date, rightX, dateY, datePaint)
        }
    }

    private fun drawMode4Right(canvas: Canvas, srcW: Int, barTop: Float, barH: Float, info: WatermarkInfo, textColor: Int, mutedColor: Int, context: Context, isPortrait: Boolean) {
        val rightMargin = barH * 0.3f
        var rightX = srcW - rightMargin

        // Params line — top
        var paramText = ""
        if (info.showParams) {
            paramText = buildParamString(info)
        }

        // Date only, strip time & seconds
        var dateNoTime = ""
        if (info.showDate) {
            dateNoTime = info.date.replace(Regex("\\s+\\d{1,2}:\\d{2}(:\\d{2})?\\s*[apAP][mM]"), "")
        }

        // Measure widths & decide vertical line offset
        val measurePaint = Paint().apply {
            textSize = PARAM_TEXT_RATIO * barH
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            // v1.3.9: 必须与实际绘制 paramPaint 的 letterSpacing 一致，
            // 否则测宽偏小 -> textLeftX 偏右 -> 竖线和 logo 整体偏右，与原版对不齐
            letterSpacing = 0.015f
            // v1.4.1: 真粗体压窄到原版字宽
            textScaleX = 1.066f
        }
        val measureDatePaint = Paint().apply {
            // v1.2.3: 与实际日期文字一致，保证竖线定位准确
            textSize = PARAM_TEXT_RATIO * barH
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            // v1.3.9: 与实际绘制 datePaint 的 letterSpacing 一致
            letterSpacing = 0.015f
            // v1.4.1: 真粗体压窄到原版字宽
            textScaleX = 1.066f
        }

        if (!info.showDate && info.showParams) {
            // Only params — right-aligned, vertically centered in bar
            val paramPaint = Paint().apply {
                color = textColor
                textSize = PARAM_TEXT_RATIO * barH
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = 0.015f
                // v1.4.1: 真粗体压窄到原版字宽
                textScaleX = 1.066f
                textAlign = Paint.Align.RIGHT
            }
            val paramTextSize = paramPaint.textSize
            val paramCenterY = barTop + barH / 2f + 0.35f * paramTextSize
            canvas.drawText(paramText, rightX, paramCenterY, paramPaint)

            val textLeftX = rightX - measurePaint.measureText(paramText)
            val lineX = textLeftX - barH * 0.145f
            drawMode4VerticalLine(canvas, lineX, barH, barTop, textColor)
            drawMode4Logo(canvas, lineX, barH, barTop, textColor, context, info)
            return
        }

        // Only date — right-aligned, vertically centered
        if (info.showDate && !info.showParams) {
            val datePaint = Paint().apply {
                color = fadeColor(textColor, 0.2f)
                // v1.2.3: 日期与参数行统一粗细/字号（NORMAL→BOLD, 0.17765→0.1764）
                textSize = PARAM_TEXT_RATIO * barH
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = 0.015f
                // v1.4.1: 真粗体压窄到原版字宽
                textScaleX = 1.066f
                textAlign = Paint.Align.RIGHT
            }
            val dateTextSize = datePaint.textSize
            val dateCenterY = barTop + barH / 2f + 0.38f * dateTextSize
            canvas.drawText(dateNoTime, rightX, dateCenterY, datePaint)

            val lineX = rightX - measureDatePaint.measureText(dateNoTime) - barH * 0.145f
            drawMode4VerticalLine(canvas, lineX, barH, barTop, textColor)
            drawMode4Logo(canvas, lineX, barH, barTop, textColor, context, info)
            return
        }

        // Normal: right-aligned with date
        if (info.showParams) {
            val paramPaint = Paint().apply {
                color = textColor
                textSize = PARAM_TEXT_RATIO * barH
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = 0.015f
                // v1.4.1: 真粗体压窄到原版字宽
                textScaleX = 1.066f
                textAlign = Paint.Align.RIGHT
            }
            val paramTextSize = paramPaint.textSize
            canvas.drawText(paramText, rightX, barTop + 0.38f * barH + 0.35f * paramTextSize, paramPaint)
        }

        if (info.showDate) {
            val datePaint = Paint().apply {
                color = fadeColor(textColor, 0.2f)
                // v1.2.3: 日期与参数行统一粗细/字号（NORMAL→BOLD, 0.17765→0.1764）
                textSize = PARAM_TEXT_RATIO * barH
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = 0.015f
                // v1.4.1: 真粗体压窄到原版字宽
                textScaleX = 1.066f
                textAlign = Paint.Align.RIGHT
            }
            val dateTextSize = datePaint.textSize
            canvas.drawText(dateNoTime, rightX, barTop + 0.64f * barH + 0.35f * dateTextSize, datePaint)
        }

        var textLeftX = rightX
        if (info.showParams) {
            textLeftX = minOf(textLeftX, rightX - measurePaint.measureText(paramText))
        }
        if (info.showDate) {
            textLeftX = minOf(textLeftX, rightX - measureDatePaint.measureText(dateNoTime))
        }

        // Vertical line separator
        val lineX = textLeftX - barH * 0.145f
        drawMode4VerticalLine(canvas, lineX, barH, barTop, textColor)
        drawMode4Logo(canvas, lineX, barH, barTop, textColor, context, info)
    }

    private fun drawMode4VerticalLine(canvas: Canvas, lineX: Float, barH: Float, barTop: Float, textColor: Int) {
        val linePaint = Paint().apply {
            color = fadeColor(textColor, 0.4f)
            strokeWidth = barH * 0.0077f
            isAntiAlias = true
        }
        canvas.drawLine(lineX, barTop + barH * 0.2837f, lineX, barTop + barH * 0.7163f, linePaint)
    }

    private fun drawMode4Logo(canvas: Canvas, lineX: Float, barH: Float, barTop: Float, textColor: Int, context: Context, info: WatermarkInfo? = null) {
        try {
            val src = BitmapFactory.decodeResource(context.resources, R.drawable.mode4_m_logo) ?: return
            if (!src.isRecycled) {
                // v1.2.6: M logo 微调到 0.745（0.717→0.745），对齐官方直径 ≈79px
                val logoH = barH * 0.782f
                val logoW = (src.width.toFloat() / src.height) * logoH
                // v1.3.9: 可见圆右缘到竖线 = 0.179*logoW + 间距 → 0.140barH + 0.02barH = 0.16barH（对齐原版）
                val logoX = lineX - logoW - barH * 0.02f
                val logoY = barTop + (barH - logoH) / 2f
                val tinted = tintBitmap(src, textColor)
                src.recycle()
                canvas.drawBitmap(tinted, null, RectF(logoX, logoY, logoX + logoW, logoY + logoH), null)
                tinted.recycle()
            }
        } catch (_: Exception) {}
    }

    // ==================== Mode 11: F1 Logo Right ====================

    private fun drawMode11Right(canvas: Canvas, srcW: Int, barTop: Float, barH: Float, info: WatermarkInfo, textColor: Int, mutedColor: Int, context: Context, isPortrait: Boolean) {
        // F1 logo: white variant on dark bg, inverted (black) variant on light bg
        val textLum = (((Color.red(textColor) * 0.299f) + (Color.green(textColor) * 0.587f)) + (Color.blue(textColor) * 0.114f)) / 255.0f
        val useLight = textLum >= 0.5f
        val f1Logo = BitmapFactory.decodeResource(context.resources, R.drawable.mode11_logo)
        var rightX = srcW - barH * 0.08f

        if (f1Logo != null && !f1Logo.isRecycled) {
            // Bigger logo: ~85% of bar height (vs default 68%)
            val logoH = barH * 0.85f
            val logoW = (f1Logo.width.toFloat() / f1Logo.height) * logoH

            val displayLogo = if (useLight) {
                // Dark text → use original (white/yellow/red actually works on dark bg? original has colors)
                // User wants: light text = original, dark text = inverted black
                f1Logo
            } else {
                // Dark text → black version via invert
                val inv = invertRightLogoForDark(f1Logo)
                f1Logo.recycle()
                inv
            }

            if (displayLogo != null && !displayLogo.isRecycled) {
                val logoX = rightX - logoW
                val logoY = barTop + (barH - logoH) / 2f
                canvas.drawBitmap(displayLogo, null, RectF(logoX, logoY, logoX + logoW, logoY + logoH), null)
                rightX = logoX - barH * 0.12f
                if (displayLogo !== f1Logo) displayLogo.recycle()
                else if (!useLight && f1Logo.isRecycled) {} else if (useLight) f1Logo.recycle()
            }
        } else {
            rightX = srcW - barH * 0.20f
        }

        // params line — shifted left
        if (info.showParams) {
            val paramPaint = Paint()
            paramPaint.color = textColor
            paramPaint.textSize = PARAM_TEXT_RATIO * barH
            paramPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            paramPaint.letterSpacing = 0.015f
            paramPaint.textAlign = Paint.Align.RIGHT
            val paramTextSize = paramPaint.textSize
            val paramY = if (!info.showDate) barTop + barH / 2f + 0.35f * paramTextSize
                         else barTop + 0.38f * barH + 0.35f * paramTextSize
            canvas.drawText(buildParamString(info), rightX, paramY, paramPaint)
        }

        // date line
        if (info.showDate) {
            val datePaint = Paint()
            datePaint.color = fadeColor(textColor, 0.17f)
            // v1.2.3: 日期与参数行统一粗细/字号（NORMAL→BOLD, 0.17765→0.168）
            datePaint.textSize = PARAM_TEXT_RATIO * barH
            datePaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            datePaint.letterSpacing = 0.015f
            datePaint.textAlign = Paint.Align.RIGHT
            val dateTextSize = datePaint.textSize
            val dateY = if (!info.showParams) barTop + barH / 2f + 0.38f * dateTextSize
                        else barTop + 0.69f * barH + 0.35f * dateTextSize
            canvas.drawText(info.date, rightX, dateY, datePaint)
        }
    }

    // ==================== Mode 5: Film Frame ====================

    // ==================== Mode 6: Minimalist Label ====================

    // ==================== Mode 7: Signature Strip ====================

    // ==================== Mode 9: Cinematic Frame ====================
    // 高级电影感画框水印，仿 Leica/Hasselblad 风格
    // 白细线画框 + 左下角品牌+型号 + 右下角参数

    // ==================== Mode 10: Camera Info Bar ====================
    // 底部全宽半透明信息条，仿专业相机直出信息带

    private fun drawTransparencyCenterMode(
        canvas: Canvas, source: Bitmap, srcW: Int, srcH: Int,
        spec: WatermarkMath.LayoutSpec, info: WatermarkInfo,
        colorScheme: SmartColorExtractor.ColorScheme, context: Context
    ) {
        canvas.drawBitmap(source, 0f, 0f, null)

        val ref = minOf(srcW, srcH)

        val deviceName = info.deviceName
            .replace("motorola ", "", ignoreCase = true)
            .replace("Motorola ", "", ignoreCase = true)
            .trim()
        val paramStr = if (info.showParams) buildParamString(info) else ""
        val dateStr = if (info.showDate) {
            info.date.replaceFirst(Regex("\\s+\\d{1,2}:\\d{2}(:\\d{2})?\\s*[apAP][mM]"), "")
        } else ""

        // ── 尺寸 ──
        val barH = ref * 0.065f
        val padX = ref * 0.035f
        val nameSz = ref * 0.022f
        val subSz = nameSz * 0.62f
        val dateSz = subSz * 0.82f

        val barTop = srcH - barH

        // ── 底部渐变遮罩（从透明到黑底的过渡，更自然）──
        val gradTop = barTop - barH * 0.6f
        val gradPaint = Paint().apply { isAntiAlias = true }
        val grad = LinearGradient(
            0f, gradTop, 0f, barTop,
            intArrayOf(Color.TRANSPARENT, Color.argb(200, 0, 0, 0)),
            null, Shader.TileMode.CLAMP
        )
        gradPaint.shader = grad
        canvas.drawRect(0f, gradTop, srcW.toFloat(), barTop.toFloat(), gradPaint)
        gradPaint.shader = null

        // ── 半透明黑底条 ──
        val bgPaint = Paint().apply {
            color = Color.argb(200, 0, 0, 0)
            isAntiAlias = true
        }
        canvas.drawRect(0f, barTop, srcW.toFloat(), srcH.toFloat(), bgPaint)

        // ── 顶部 accent 细线 ──
        val accent = colorScheme.accent
        val lum = SmartColorExtractor.luminance(accent)
        val lineColor = if (lum < 0.25f) {
            // 暗色 accent → 提亮
            val r = kMin(255, (Color.red(accent) * 1.6f).toInt())
            val g = kMin(255, (Color.green(accent) * 1.6f).toInt())
            val b = kMin(255, (Color.blue(accent) * 1.6f).toInt())
            Color.argb(200, r, g, b)
        } else {
            Color.argb(200, Color.red(accent), Color.green(accent), Color.blue(accent))
        }
        val linePaint = Paint().apply {
            color = lineColor
            strokeWidth = ref * 0.0015f
            isAntiAlias = true
        }
        canvas.drawLine(padX * 0.5f, barTop, srcW - padX * 0.5f, barTop, linePaint)

        // ── Paint ──
        val namePaint = Paint().apply {
            color = Color.argb(245, 255, 255, 255)
            textSize = nameSz
            typeface = Typeface.create(getMontserratTypeface(context), Typeface.BOLD)
            textAlign = Paint.Align.LEFT
            isFakeBoldText = true
        }

        val paramPaint = Paint().apply {
            color = Color.argb(200, 255, 255, 255)
            textSize = subSz
            typeface = getMontserratTypeface(context)
            textAlign = Paint.Align.RIGHT
        }

        val datePaint = Paint().apply {
            color = Color.argb(130, 255, 255, 255)
            textSize = dateSz
            typeface = getMontserratTypeface(context)
            textAlign = Paint.Align.RIGHT
        }

        // 所有文字垂直居中于 bar 内
        // 单行基线 = barTop + barH/2 + ascent/2 修正（使 text 真正居中）
        val fontMetrics = namePaint.fontMetrics
        val baseline = barTop + (barH - fontMetrics.ascent - fontMetrics.descent) / 2f

        // ── 左：logo + 机型名 ──
        var cx = padX
        val logo = getMotorolaLogo(context)
        if (logo != null && !logo.isRecycled) {
            val logoH = nameSz * 0.82f
            val lw = (logo.width.toFloat() / logo.height) * logoH
            val ly = barTop + (barH - logoH) / 2f
            val tinted = tintBitmap(logo, Color.argb(220, 255, 255, 255))
            canvas.drawBitmap(tinted, null, RectF(cx, ly, cx + lw, ly + logoH), null)
            tinted.recycle()
            cx += lw + nameSz * 0.5f
        }
        // v1.1.8: Signature 机型用花体替代机型名文字（放大）
        // v1.2.8: 花体放大到与其它样式一致的比例（0.685→0.892，笔画高/字标高≈1.77），视觉对齐协调
        if (!drawSignatureIfNeeded(canvas, deviceName, context, cx, barTop + barH / 2f, barH * 0.892f, Color.argb(245, 255, 255, 255), Paint.Align.LEFT)) {
            // v1.3.2: 普通机型文字按实际墨迹中心对齐字标中心线（与花体同一条线）
            canvas.drawText(deviceName, cx, baselineForVisualCenter(namePaint, deviceName, barTop + barH / 2f), namePaint)
        }

        // ── 右：参数 + 日期 ──
        if (paramStr.isNotBlank() || dateStr.isNotBlank()) {
            val rx = srcW - padX
            if (dateStr.isNotBlank()) {
                canvas.drawText(dateStr, rx.toFloat(), baseline, datePaint)
            }
            if (paramStr.isNotBlank()) {
                val paramBaseline = if (dateStr.isNotBlank()) baseline - subSz * 1.15f else baseline
                canvas.drawText(paramStr, rx.toFloat(), paramBaseline, paramPaint)
            }
        }

        // Owner name top-left
        if (info.ownerName.isNotBlank()) {
            val nameSize = subSz * 0.7f
            val atSize = nameSize * 1.25f
            val atPaint = Paint().apply {
                color = Color.argb(160, 255, 255, 255)
                textSize = atSize
                typeface = getMontserratTypeface(context)
                textAlign = Paint.Align.LEFT
            }
            val namePaint = Paint().apply {
                color = Color.argb(160, 255, 255, 255)
                textSize = nameSize
                typeface = getMontserratTypeface(context)
                textAlign = Paint.Align.LEFT
            }
            val y = barTop - barH * 0.15f
            canvas.drawText("@", padX, y, atPaint)
            canvas.drawText(info.ownerName, padX + atPaint.measureText("@"), y + nameBaselineOffset(atPaint, namePaint, info.ownerName), namePaint)
        }
    }

    /**
     * v1.2.4: Pure 纯透明样式
     * 无底栏、无遮罩，直接在原图上绘制：左侧圆形 M logo + motorola 字标 + 机型（signature 花体或文字）。
     * 颜色纯白或纯黑（useWhiteText: false=黑，true/null=白）。
     * v1.2.7: 三个元素尺寸与 Classic 完全一致（复用 spec.barH 与同一套系数），并水平居中对齐。
     */
    private fun drawPureMode(
        canvas: Canvas, source: Bitmap, srcW: Int, srcH: Int,
        spec: WatermarkMath.LayoutSpec, info: WatermarkInfo, context: Context
    ) {
        canvas.drawBitmap(source, 0f, 0f, null)

        val barH = spec.barH
        val pad = barH * 0.25f
        // 纯透明：只显示白或黑，null 默认白
        val color = if (info.useWhiteText == false) Color.rgb(16, 16, 16) else Color.rgb(255, 255, 255)

        // ---- v1.3.1: 按官方手签版比例重做，以 motorola 字高为唯一基准 ----
        // 官方参考图实测比例(字高=1)：M logo 圆直径=1.62，signature 花体笔画高=1.89
        val logo = getMotorolaLogo(context)
        val logoRatio = logo?.let { it.width.toFloat() / it.height } ?: (981f / 170f)
        val actualLogoH = barH * 0.28f                 // motorola 字高：放大作主视觉
        val actualLogoW = logoRatio * actualLogoH
        // M logo 可见圆 = 字高×1.62；mode4_m_logo 可见圆占图高 578/910≈0.635 → 反推图片框高
        val mLogoH = (actualLogoH * 1.62f) / (578f / 910f)
        // signature 花体笔画高 = 字高×1.89；signature_script 笔画占图高≈0.554 → 反推图片框高
        val sigBoxH = (actualLogoH * 1.89f) / 0.554f

        // 三者垂直中心统一在字标中心 → 主体带中心对齐（圆/字/花体视觉同一水平线）
        val centerY = srcH.toFloat() - pad - actualLogoH / 2f
        val gap = barH * 0.0693f

        var cx = pad

        // 1) 左侧圆形 M logo（mode4_m_logo：原图黑圆白M；白字模式反色为白圆黑M，保留双色层次）
        try {
            val mlogo = BitmapFactory.decodeResource(context.resources, R.drawable.mode4_m_logo)
            if (mlogo != null && !mlogo.isRecycled) {
                val lw = (mlogo.width.toFloat() / mlogo.height) * mLogoH
                val display = if (info.useWhiteText != false) invertLogoColors(mlogo) else mlogo
                canvas.drawBitmap(display, null, RectF(cx, centerY - mLogoH / 2f, cx + lw, centerY + mLogoH / 2f), null)
                if (display !== mlogo) display.recycle()
                mlogo.recycle()
                cx += lw + gap
            }
        } catch (_: Exception) {}

        // 2) motorola 字标（缓存位图，不 recycle 原图）
        // v1.3.6 [B方案]: 字标墨迹中心在图片高度58%处（偏下），把绘制框上移0.08×H，
        // 使字标墨迹中心精确落在 centerY，与 M logo 圆心、机型文字严格同一水平线。
        if (logo != null && !logo.isRecycled) {
            val tinted = tintBitmap(logo, color)
            canvas.drawBitmap(tinted, null, RectF(cx, centerY - actualLogoH * 0.58f, cx + actualLogoW, centerY + actualLogoH * 0.42f), null)
            tinted.recycle()
            cx += actualLogoW + gap
        }

        // 3) 机型：signature 花体或普通文字（与 Classic 同样左移贴合字标）
        val deviceName = info.deviceName
            .replace("motorola ", "", ignoreCase = true)
            .replace("Motorola ", "", ignoreCase = true)
            .trim()
        if (!drawSignatureIfNeeded(canvas, deviceName, context, cx - barH * 0.06f, centerY, sigBoxH, color, Paint.Align.LEFT)) {
            val namePaint = Paint().apply {
                this.color = color
                // v1.3.3: 缩小机型文字匹配 motorola 字标（与 Classic 一致，0.2406→0.20）
                textSize = 0.20f * barH
                typeface = Typeface.create(getMontserratTypeface(context), Typeface.BOLD)
                textAlign = Paint.Align.LEFT
                isAntiAlias = true
            }
            // v1.3.2: 普通机型文字按实际墨迹中心对齐圆/字标中心线
            val text = deviceName.lowercase()
            canvas.drawText(text, cx, baselineForVisualCenter(namePaint, text, centerY), namePaint)
        }
    }

    fun render(source: Bitmap, info: WatermarkInfo, context: Context): Bitmap {
        // 如果所有信息为空，直接返回原图
        if (info.deviceName.isBlank() && info.focalLength.isBlank() && info.aperture.isBlank() &&
            info.shutterSpeed.isBlank() && info.iso.isBlank() && info.date.isBlank()) {
            return source
        }

        val w = source.width
        val h = source.height
        val spec = WatermarkMath.computeLayoutSpec(w, h)
        val outputH = if (info.style == 1 || info.style == 4 || info.style == 11) h + spec.barH.toInt() else h
        val output = Bitmap.createBitmap(w, outputH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val colorScheme = when {
            info.manualPickColor != null -> {
                val lum = SmartColorExtractor.luminance(info.manualPickColor)
                SmartColorExtractor.ColorScheme(
                    background = info.manualPickColor,
                    text = if (lum > 0.5f) Color.BLACK else Color.WHITE,
                    textSecondary = if (lum > 0.5f) Color.DKGRAY else Color.LTGRAY,
                    accent = info.manualPickColor,
                    accentMuted = SmartColorExtractor.desaturate(info.manualPickColor, 0.5f)
                )
            }
            info.barbiePink -> SmartColorExtractor.extractBarbie(source)
            info.originalColor -> SmartColorExtractor.extractOriginal(source)
            info.richSaturation -> SmartColorExtractor.extractRich(source)
            else -> SmartColorExtractor.extract(source)
        }

        val extend = info.style == 1 || info.style == 4 || info.style == 11
        // v1.1.5: 保留 Standard(1)/Corner(3)/Classic(4)/Transp.(10)/F1(11)
        // v1.2.4: 新增 Pure(12) 纯透明样式
        when (info.style) {
            3 -> drawDiagonalMode(canvas, source, w, outputH, spec, info, colorScheme, context)
            4 -> drawBarMode(canvas, source, w, outputH, spec, info, colorScheme, context, extend)
            10 -> drawTransparencyCenterMode(canvas, source, w, outputH, spec, info, colorScheme, context)
            12 -> drawPureMode(canvas, source, w, outputH, spec, info, context)
            else -> drawBarMode(canvas, source, w, outputH, spec, info, colorScheme, context, extend)
        }

        return output
    }
}
