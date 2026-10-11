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
        canvas.drawText(deviceName, rightX, bottomY, namePaint)

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
        val maxLogoW = barH * 1.05f
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
                textSize = 0.2406f * barH
                this.typeface = typeface
                textAlign = Paint.Align.LEFT
                isFakeBoldText = false
            }
            val suffixCenterY = logoTop + actualLogoH / 2f
            val suffixBaseline = suffixCenterY + suffixPaint.textSize * 0.38f
            val suffixX = if (info.showLogo) logoStartX + actualLogoW + barH * 0.0693f else logoStartX
            canvas.drawText(suffix.lowercase(), suffixX, suffixBaseline, suffixPaint)
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
            paramPaint.textSize = (if (isPortrait) 0.168f else 0.21f) * barH
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
            datePaint.textSize = 0.17765f * barH
            datePaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
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
            textSize = (if (isPortrait) 0.1764f else 0.21f) * barH
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val measureDatePaint = Paint().apply {
            textSize = 0.17765f * barH
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        if (!info.showDate && info.showParams) {
            // Only params — right-aligned, vertically centered in bar
            val paramPaint = Paint().apply {
                color = textColor
                textSize = (if (isPortrait) 0.1764f else 0.21f) * barH
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = 0.015f
                textAlign = Paint.Align.RIGHT
            }
            val paramTextSize = paramPaint.textSize
            val paramCenterY = barTop + barH / 2f + 0.35f * paramTextSize
            canvas.drawText(paramText, rightX, paramCenterY, paramPaint)

            val textLeftX = rightX - measurePaint.measureText(paramText)
            val lineX = textLeftX - barH * 0.2415f
            drawMode4VerticalLine(canvas, lineX, barH, barTop, textColor)
            drawMode4Logo(canvas, lineX, barH, barTop, textColor, context, info)
            return
        }

        // Only date — right-aligned, vertically centered
        if (info.showDate && !info.showParams) {
            val datePaint = Paint().apply {
                color = fadeColor(textColor, 0.2f)
                textSize = 0.17765f * barH
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                letterSpacing = 0.015f
                textAlign = Paint.Align.RIGHT
            }
            val dateTextSize = datePaint.textSize
            val dateCenterY = barTop + barH / 2f + 0.38f * dateTextSize
            canvas.drawText(dateNoTime, rightX, dateCenterY, datePaint)

            val lineX = rightX - measureDatePaint.measureText(dateNoTime) - barH * 0.2415f
            drawMode4VerticalLine(canvas, lineX, barH, barTop, textColor)
            drawMode4Logo(canvas, lineX, barH, barTop, textColor, context, info)
            return
        }

        // Normal: right-aligned with date
        if (info.showParams) {
            val paramPaint = Paint().apply {
                color = textColor
                textSize = (if (isPortrait) 0.1764f else 0.21f) * barH
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                letterSpacing = 0.015f
                textAlign = Paint.Align.RIGHT
            }
            val paramTextSize = paramPaint.textSize
            canvas.drawText(paramText, rightX, barTop + 0.38f * barH + 0.35f * paramTextSize, paramPaint)
        }

        if (info.showDate) {
            val datePaint = Paint().apply {
                color = fadeColor(textColor, 0.2f)
                textSize = 0.17765f * barH
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                letterSpacing = 0.015f
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
        val lineX = textLeftX - barH * 0.2415f
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
                val logoH = barH * 0.875f
                val logoW = (src.width.toFloat() / src.height) * logoH
                val logoX = lineX - logoW - barH * 0.07f
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
            paramPaint.textSize = (if (isPortrait) 0.168f else 0.21f) * barH
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
            datePaint.textSize = 0.17765f * barH
            datePaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
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
        canvas.drawText(deviceName, cx, baseline, namePaint)

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
        // v1.1.5: 只保留 5 种模式 Standard(1)/Corner(3)/Classic(4)/Transp.(10)/F1(11)
        when (info.style) {
            3 -> drawDiagonalMode(canvas, source, w, outputH, spec, info, colorScheme, context)
            4 -> drawBarMode(canvas, source, w, outputH, spec, info, colorScheme, context, extend)
            10 -> drawTransparencyCenterMode(canvas, source, w, outputH, spec, info, colorScheme, context)
            else -> drawBarMode(canvas, source, w, outputH, spec, info, colorScheme, context, extend)
        }

        return output
    }
}
