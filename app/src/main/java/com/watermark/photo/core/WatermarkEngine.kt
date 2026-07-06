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

    private fun adjustColorForContrast(textColor: Int, bgColor: Int): Int {
        val bgLum = (((Color.red(bgColor) * 0.299f) + (Color.green(bgColor) * 0.587f)) + (Color.blue(bgColor) * 0.114f)) / 255.0f
        val colorLum = (((Color.red(textColor) * 0.299f) + (Color.green(textColor) * 0.587f)) + (Color.blue(textColor) * 0.114f)) / 255.0f
        val diff = abs(colorLum - bgLum)
        if (diff >= 0.12f) return textColor
        val adjust = 0.12f - diff
        return if (bgLum > 0.5f) {
            val delta = (adjust * 255).toInt()
            Color.rgb(
                kMax(0, Color.red(textColor) - delta),
                kMax(0, Color.green(textColor) - delta),
                kMax(0, Color.blue(textColor) - delta)
            )
        } else {
            val delta = (adjust * 255).toInt()
            Color.rgb(
                kMin(255, Color.red(textColor) + delta),
                kMin(255, Color.green(textColor) + delta),
                kMin(255, Color.blue(textColor) + delta)
            )
        }
    }

    /** 颜色淡化：向白色混合 fadeRatio（0~1），比直接调 alpha 更稳定 */
    private fun fadeColor(color: Int, fadeRatio: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * fadeRatio).toInt()
        val g = (Color.green(color) + (255 - Color.green(color)) * fadeRatio).toInt()
        val b = (Color.blue(color) + (255 - Color.blue(color)) * fadeRatio).toInt()
        return Color.rgb(r, g, b)
    }

    private fun blendForContrast(accent: Int, baseContrast: Int, blendRatio: Float): Int {
        val inv = 1 - blendRatio
        return adjustColorForContrast(
            Color.rgb(
                ((Color.red(baseContrast) * blendRatio) + (Color.red(accent) * inv)).toInt().coerceIn(0, 255),
                ((Color.green(baseContrast) * blendRatio) + (Color.green(accent) * inv)).toInt().coerceIn(0, 255),
                ((Color.blue(baseContrast) * blendRatio) + (Color.blue(accent) * inv)).toInt().coerceIn(0, 255)
            ),
            baseContrast
        )
    }

    private fun blendWithAccentMode2(base: Int, accent: Int, ratio: Float): Int {
        val inv = 1 - ratio
        return Color.rgb(
            ((Color.red(base) * inv) + (Color.red(accent) * ratio)).toInt().coerceIn(0, 255),
            ((Color.green(base) * inv) + (Color.green(accent) * ratio)).toInt().coerceIn(0, 255),
            ((Color.blue(base) * inv) + (Color.blue(accent) * ratio)).toInt().coerceIn(0, 255)
        )
    }

    private fun boxBlur(source: Bitmap, radius: Int): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var cnt = 0
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val px = (x + dx).coerceIn(0, w - 1)
                        val py = (y + dy).coerceIn(0, h - 1)
                        val p = pixels[py * w + px]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        cnt++
                    }
                }
                out[y * w + x] = (0xFF000000.toInt()) or
                    (((r / cnt).coerceIn(0, 255)) shl 16) or
                    (((g / cnt).coerceIn(0, 255)) shl 8) or
                    ((b / cnt).coerceIn(0, 255))
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }



    private fun clipRoundRect(canvas: Canvas, rect: RectF, rx: Float, ry: Float) {
        val path = Path()
        path.addRoundRect(rect, rx, ry, Path.Direction.CW)
        canvas.clipPath(path)
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

    private fun contrastRatio(c1: Int, c2: Int): Float {
        val l1 = (((Color.red(c1) * 0.299f) + (Color.green(c1) * 0.587f)) + (Color.blue(c1) * 0.114f)) / 255.0f
        val l2 = (((Color.red(c2) * 0.299f) + (Color.green(c2) * 0.587f)) + (Color.blue(c2) * 0.114f)) / 255.0f
        return (kotlin.math.max(l1, l2) + 0.05f) / (kotlin.math.min(l1, l2) + 0.05f)
    }

    private fun createBlurredBackground(source: Bitmap): Bitmap {
        // 限制最大输出尺寸，避免大图OOM
        val maxDim = 1920
        val div = 8  // 下采样除数（越小细节越多，模糊越平滑）
        val scale = minOf(1.0f, maxDim.toFloat() / maxOf(source.width, source.height))
        val smallW = maxOf((source.width * scale / div).toInt(), 4)
        val smallH = maxOf((source.height * scale / div).toInt(), 4)
        val small = Bitmap.createScaledBitmap(source, smallW, smallH, true)
        // 双缓冲 boxBlur（14 pass），避免反复分配临时位图
        var cur = boxBlur(small, 8)
        small.recycle()
        repeat(8) {
            val next = boxBlur(cur, 8)
            cur.recycle()
            cur = next
        }
        // 输出 ARGB_8888 避免 RGB_565 色深不足导致的条带
        val outW = minOf(source.width, (source.width * scale).toInt())
        val outH = minOf(source.height, (source.height * scale).toInt())
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val resultCanvas = Canvas(result)
        resultCanvas.drawBitmap(cur, null, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        cur.recycle()
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

    private fun drawBlurFrameMode(canvas: Canvas, source: Bitmap, srcW: Int, srcH: Int, spec: WatermarkMath.LayoutSpec, info: WatermarkInfo, colorScheme: SmartColorExtractor.ColorScheme, context: Context) {
        // 模糊背景（全屏）
        val blurred = createBlurredBackground(source)
        canvas.drawBitmap(blurred, null, RectF(0f, 0f, srcW.toFloat(), srcH.toFloat()), null)
        blurred.recycle()

        // 预留底部水印条空间
        val barAreaH = spec.barH * 1.6f
        val frameMargin = srcW * 0.018f
        val frameW = srcW - frameMargin * 2
        val frameH = srcH - frameMargin * 2 - barAreaH // 减去水印条高度
        val centerX = srcW / 2f
        val centerY = (srcH - barAreaH) / 2f // 垂直居中于剩余空间

        // 根据图片宽高比计算帧尺寸
        val imgRatio = source.width.toFloat() / source.height
        val (frameW2, frameH2) = if (imgRatio > frameW / frameH) {
            frameW to (frameW / imgRatio)
        } else {
            (imgRatio * frameH) to frameH
        }

        val halfW = frameW2 / 2f
        val halfH = frameH2 / 2f
        val left = centerX - halfW
        val top = centerY - halfH
        val right = centerX + halfW
        val bottom = centerY + halfH

        // 四周暗角渐变
        val edge = srcW * 0.10f
        val gradientPaint = Paint()
        gradientPaint.shader = LinearGradient(left, 0f, left - edge, 0f, intArrayOf(0, Color.argb(30, 0, 0, 0)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, left, srcH.toFloat(), gradientPaint)
        gradientPaint.shader = LinearGradient(right, 0f, right + edge, 0f, intArrayOf(0, Color.argb(30, 0, 0, 0)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(right, 0f, srcW.toFloat(), srcH.toFloat(), gradientPaint)
        gradientPaint.shader = LinearGradient(0f, top, 0f, top - edge, intArrayOf(0, Color.argb(25, 0, 0, 0)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, srcW.toFloat(), top, gradientPaint)

        // 多层阴影 — 渲染到软件位图（BlurMaskFilter 在硬件加速 Canvas 无效）
        val shadowCorner = frameW2 * 0.04f
        val shadowLayers = listOf(
            Triple(0f, 40f, 12) to 30f,
            Triple(0f, 28f, 20) to 22f,
            Triple(0f, 18f, 32) to 16f,
            Triple(0f, 10f, 48) to 10f,
            Triple(0f, 4f, 80) to 5f,
            Triple(0f, 0f, 100) to 2f
        )
        val maxBlur = 30f
        val maxOffset = 40f
        val shadowPad = (maxBlur + maxOffset + shadowCorner + 4f).toInt()
        val shadowLeft = (left - shadowPad).coerceAtLeast(0f)
        val shadowTop = (top - shadowPad).coerceAtLeast(0f)
        val shadowRight = (right + shadowPad).coerceAtMost(srcW.toFloat())
        val shadowBottom = (bottom + shadowPad).coerceAtMost(srcH.toFloat())
        val shadowW = (shadowRight - shadowLeft).toInt()
        val shadowH = (shadowBottom - shadowTop).toInt()
        if (shadowW > 0 && shadowH > 0) {
            val shadowScale = 0.25f
            val shadowBmp = Bitmap.createBitmap(
                maxOf((shadowW * shadowScale).toInt(), 1),
                maxOf((shadowH * shadowScale).toInt(), 1),
                Bitmap.Config.ARGB_8888
            )
            val shadowCanv = Canvas(shadowBmp)
            shadowCanv.scale(shadowScale, shadowScale)
            shadowCanv.translate(-shadowLeft, -shadowTop)
            val shadowPaint = Paint()
            for ((t, blur) in shadowLayers) {
                val (dx, dy, alpha) = t
                shadowPaint.color = Color.argb(alpha, 0, 0, 0)
                shadowPaint.maskFilter = BlurMaskFilter(blur * shadowScale, BlurMaskFilter.Blur.NORMAL)
                shadowCanv.drawRoundRect(
                    RectF(left + dx, top + dy, right + dx, bottom + dy),
                    shadowCorner + blur * 0.5f, shadowCorner + blur * 0.5f, shadowPaint
                )
            }
            shadowPaint.maskFilter = null
            canvas.save()
            canvas.translate(shadowLeft, shadowTop)
            canvas.scale(1f / shadowScale, 1f / shadowScale)
            canvas.drawBitmap(shadowBmp, 0f, 0f, null)
            canvas.restore()
            shadowBmp.recycle()
        }

        // 绘制照片（圆角）
        val imgRect = RectF(left, top, right, bottom)
        canvas.save()
        clipRoundRect(canvas, imgRect, shadowCorner, shadowCorner)
        canvas.drawBitmap(source, null, imgRect, Paint())
        canvas.restore()

        // ===== 底部水印区域：直接在全屏模糊背景上叠加文字 =====
        // 手动取色优先，否则从照片取色
        val mode2 = if (info.manualPickColor != null) {
            val text = if (SmartColorExtractor.luminance(info.manualPickColor) > 0.5f) Color.BLACK else Color.WHITE
            SmartColorExtractor.Mode2Colors(
                logoColor = info.manualPickColor,
                textColor = text,
                paramColor = SmartColorExtractor.adjustAlpha(text, 0.75f),
                background = info.manualPickColor
            )
        } else {
            SmartColorExtractor.extractMode2Colors(source)
        }
        val logoColor = mode2.logoColor
        val textColor = mode2.textColor
        val paramColor = Color.argb(180, Color.red(textColor), Color.green(textColor), Color.blue(textColor))

        // 底部条固定在屏幕底部
        val barTop = srcH - barAreaH

        // 渐变衬底加深，保证文字在任何背景下可读
        val scrimPaint = Paint()
        scrimPaint.shader = LinearGradient(
            0f, barTop, 0f, srcH.toFloat(),
            intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(140, 0, 0, 0)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, barTop, srcW.toFloat(), srcH.toFloat(), scrimPaint)

                // Four-row center layout
        val barCenterX = srcW / 2f
        val rowH = barAreaH / 4f
        val row1Y = barTop + rowH * 0.35f     // Logo center Y
        val row2Y = barTop + rowH * 1.15f    // Device name center Y
        val row3Y = barTop + rowH * 1.95f    // Params center Y
        val row4Y = barTop + rowH * 2.75f    // Date center Y

        // Row 1: Moto Logo (centered)
        var motoLogo: Bitmap? = try { BitmapFactory.decodeResource(context.resources, R.drawable.motorola) } catch (e: Exception) { null }
        if (motoLogo != null && !motoLogo.isRecycled) {
            val logoH = rowH * 1.4f
            val logoW = (motoLogo.width.toFloat() / motoLogo.height) * logoH
            val logoX = barCenterX - logoW / 2f
            val tintedLogo = tintBitmap(motoLogo, logoColor)
            canvas.drawBitmap(tintedLogo, null, RectF(logoX, row1Y - logoH / 2f, logoX + logoW, row1Y + logoH / 2f), null)
            tintedLogo.recycle()
            motoLogo.recycle()
        }

        // Row 2: Device name (largest text, with shadow)
        val deviceName = info.deviceName
            .replace("motorola ", "", ignoreCase = true)
            .replace("Motorola ", "", ignoreCase = true)
            .trim()
        val namePaint = Paint()
        namePaint.color = textColor
        namePaint.textSize = rowH * 0.32f
        namePaint.typeface = Typeface.create(getMontserratTypeface(context), Typeface.BOLD)
        namePaint.textAlign = Paint.Align.CENTER
        namePaint.isFakeBoldText = true
        namePaint.setShadowLayer(rowH * 0.04f, 0f, rowH * 0.03f, Color.argb(80, 0, 0, 0))
        canvas.drawText(deviceName, barCenterX, row2Y + namePaint.textSize * 0.36f, namePaint)
        // Row 3: Params (medium text, with shadow)
        if (info.showParams) {
            val paramPaint = Paint()
            paramPaint.color = paramColor
            paramPaint.textSize = rowH * 0.28f
            paramPaint.typeface = getMontserratTypeface(context)
            paramPaint.textAlign = Paint.Align.CENTER
            paramPaint.setShadowLayer(rowH * 0.03f, 0f, rowH * 0.025f, Color.argb(100, 0, 0, 0))
            canvas.drawText(buildParamString(info), barCenterX, row3Y + paramPaint.textSize * 0.36f, paramPaint)
        }
        // Row 4: Date (smallest text, with shadow)
        if (info.showDate) {
            val datePaint = Paint()
            datePaint.color = paramColor
            datePaint.textSize = rowH * 0.22f
            datePaint.typeface = getMontserratTypeface(context)
            datePaint.textAlign = Paint.Align.CENTER
            datePaint.setShadowLayer(rowH * 0.025f, 0f, rowH * 0.02f, Color.argb(100, 0, 0, 0))
            canvas.drawText(info.date, barCenterX, row4Y + datePaint.textSize * 0.36f, datePaint)
        }
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
        val baseline = barTop + barH * 0.62f

        // Motorola logo 图片（着色替换文字 "motorola"）
        val logo = getMotorolaLogo(context)
        if (logo != null && !logo.isRecycled) {
            val logoStartX = startX + barH * 0.06f  // logo左边留空
            val desiredLogoH = barH * 0.2143f
            val logoW = (logo.width.toFloat() / logo.height) * desiredLogoH
            // 长型号名(如 razr ultra 2025)时限制 logo 最大宽度，防止左边溢出
            val maxLogoW = barH * 1.05f
            val (actualLogoW, actualLogoH) = if (logoW > maxLogoW) {
                maxLogoW to (maxLogoW / (logo.width.toFloat() / logo.height))
            } else {
                logoW to desiredLogoH
            }
            val logoTop = barTop + (barH - actualLogoH) * 0.525f
            val tinted = tintBitmap(logo, textColor)
            canvas.drawBitmap(tinted, null, RectF(logoStartX, logoTop, logoStartX + actualLogoW, logoTop + actualLogoH), null)
            tinted.recycle()

            if (suffix.isNotEmpty()) {
                val suffixPaint = Paint().apply {
                    color = textColor
                    textSize = 0.2406f * barH
                    this.typeface = typeface
                    textAlign = Paint.Align.LEFT
                    isFakeBoldText = false
                }
                // 文字与logo垂直居中
                val suffixBaseline = barTop + barH * 0.605f
                val suffixX = logoStartX + actualLogoW + barH * 0.0693f  // logo与文字间距
                canvas.drawText(suffix.lowercase(), suffixX, suffixBaseline, suffixPaint)
            }
        }
    }

    private fun drawRightSection(canvas: Canvas, srcW: Int, barTop: Float, barH: Float, info: WatermarkInfo, textColor: Int, mutedColor: Int, context: Context, isPortrait: Boolean = false, style: Int = 0) {
        if (style == 4) {
            drawMode4Right(canvas, srcW, barTop, barH, info, textColor, mutedColor, context, isPortrait)
            return
        }
        var rightX = srcW - barH * 0.2f

        // 深色文字时翻转 logo：白色→黑(#1A1A1A)，其他→白(#F5F5F5)
        info.rightLogo?.let { logo ->
            if (!logo.isRecycled) {
                val textLum = (((Color.red(textColor) * 0.299f) + (Color.green(textColor) * 0.587f)) + (Color.blue(textColor) * 0.114f)) / 255.0f
                val drawLogo = if (textLum < 0.5f) logo else invertRightLogoForDark(logo)
                val logoH = barH * 0.68f
                val logoW = (logo.width.toFloat() / logo.height) * logoH
                val logoX = rightX - logoW
                val logoY = barTop + (barH - logoH) / 2f
                canvas.drawBitmap(drawLogo, null, RectF(logoX, logoY, logoX + logoW, logoY + logoH), null)
                if (drawLogo !== logo) drawLogo.recycle()
                rightX = logoX - barH * 0.2f
            }
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

        // date line — 下方 69% 位置，与参数行间距收拢
        if (info.showDate) {
            val datePaint = Paint()
            datePaint.color = fadeColor(textColor, 0.17f)
            datePaint.textSize = 0.17765f * barH
            datePaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            datePaint.letterSpacing = 0.015f
            datePaint.textAlign = Paint.Align.RIGHT
            val dateTextSize = datePaint.textSize
            canvas.drawText(info.date, rightX, barTop + 0.69f * barH + 0.35f * dateTextSize, datePaint)
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
            drawMode4Logo(canvas, lineX, barH, barTop, textColor, context)
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
        drawMode4Logo(canvas, lineX, barH, barTop, textColor, context)
    }

    private fun drawMode4VerticalLine(canvas: Canvas, lineX: Float, barH: Float, barTop: Float, textColor: Int) {
        val linePaint = Paint().apply {
            color = fadeColor(textColor, 0.4f)
            strokeWidth = barH * 0.0077f
            isAntiAlias = true
        }
        canvas.drawLine(lineX, barTop + barH * 0.2837f, lineX, barTop + barH * 0.7163f, linePaint)
    }

    private fun drawMode4Logo(canvas: Canvas, lineX: Float, barH: Float, barTop: Float, textColor: Int, context: Context) {
        try {
            val src = BitmapFactory.decodeResource(context.resources, R.drawable.mode4_m_logo) ?: return
            if (!src.isRecycled) {
                val logoH = barH * 0.729f
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

    // ==================== Mode 5: Film Frame ====================

    private fun drawFilmFrameMode(canvas: Canvas, source: Bitmap, srcW: Int, srcH: Int, spec: WatermarkMath.LayoutSpec, info: WatermarkInfo, colorScheme: SmartColorExtractor.ColorScheme, context: Context) {
        canvas.drawBitmap(source, 0f, 0f, null)

        val topStripH = srcH * 0.08f
        val bottomStripH = srcH * 0.10f

        // Top gradient (black → transparent)
        val topPaint = Paint()
        topPaint.shader = LinearGradient(
            0f, 0f, 0f, topStripH,
            intArrayOf(Color.argb(190, 0, 0, 0), Color.argb(0, 0, 0, 0)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, srcW.toFloat(), topStripH, topPaint)

        // Bottom gradient (transparent → black)
        val bottomTop = srcH - bottomStripH
        val bottomPaint = Paint()
        bottomPaint.shader = LinearGradient(
            0f, bottomTop, 0f, srcH.toFloat(),
            intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(220, 0, 0, 0)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, bottomTop, srcW.toFloat(), srcH.toFloat(), bottomPaint)

        // Film sprocket holes on left and right edges
        val holePaint = Paint().apply {
            color = Color.argb(50, 255, 255, 255)
            isAntiAlias = true
        }
        val holeRadius = srcW * 0.007f
        val holeSpacing = srcH * 0.048f
        val holeStartY = topStripH * 0.6f
        val holeEndY = bottomTop + bottomStripH * 0.4f
        for (side in listOf(holeRadius * 2.5f, srcW - holeRadius * 2.5f)) {
            var y = holeStartY
            while (y < holeEndY) {
                canvas.drawCircle(side, y, holeRadius, holePaint)
                y += holeSpacing
            }
        }

        // Text in bottom strip
        val textWhite = Color.argb(240, 255, 255, 255)
        val textMuted = Color.argb(160, 255, 255, 255)
        val centerX = srcW / 2f
        val deviceName = info.deviceName
            .replace("motorola ", "", ignoreCase = true)
            .replace("Motorola ", "", ignoreCase = true)
            .trim()

        val namePaint = Paint().apply {
            color = textWhite
            textSize = bottomStripH * 0.30f
            typeface = Typeface.create(getMontserratTypeface(context), Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            setShadowLayer(2f, 0f, 1f, Color.argb(100, 0, 0, 0))
        }
        val nameY = bottomTop + bottomStripH * 0.38f
        canvas.drawText(deviceName, centerX, nameY, namePaint)

        if (info.showParams) {
            val paramPaint = Paint().apply {
                color = textMuted
                textSize = bottomStripH * 0.20f
                typeface = getMontserratTypeface(context)
                textAlign = Paint.Align.CENTER
            }
            val paramY = bottomTop + bottomStripH * 0.65f
            canvas.drawText(buildParamString(info), centerX, paramY, paramPaint)
        }

        // "FILM" stamp bottom-left
        val stampPaint = Paint().apply {
            color = Color.argb(80, 255, 255, 255)
            textSize = bottomStripH * 0.18f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText("\u25C6 FILM", srcW * 0.03f, srcH - bottomStripH * 0.22f, stampPaint)
    }

    // ==================== Mode 6: Minimalist Label ====================

    private fun drawMinimalistLabelMode(canvas: Canvas, source: Bitmap, srcW: Int, srcH: Int, spec: WatermarkMath.LayoutSpec, info: WatermarkInfo, colorScheme: SmartColorExtractor.ColorScheme, context: Context) {
        canvas.drawBitmap(source, 0f, 0f, null)

        val isPortrait = srcH > srcW
        val pad = minOf(srcW, srcH) * 0.03f

        // Build label text
        val deviceName = info.deviceName
            .replace("motorola ", "", ignoreCase = true)
            .replace("Motorola ", "", ignoreCase = true)
            .trim()
        val hasParams = info.showParams && buildParamString(info).isNotBlank()
        val dateStr = if (info.showDate && info.date.isNotBlank()) {
            info.date.replace(Regex("\\s+\\d{1,2}:\\d{2}(:\\d{2})?\\s*[apAP][mM]"), "")
        } else ""

        // Measure text widths
        val labelTextSize = minOf(srcW, srcH) * 0.025f
        val subTextSize = labelTextSize * 0.68f

        val namePaint = Paint().apply {
            color = Color.WHITE
            textSize = labelTextSize
            typeface = Typeface.create(getMontserratTypeface(context), Typeface.BOLD)
            isFakeBoldText = true
            textAlign = Paint.Align.LEFT
        }
        val nameW = namePaint.measureText(deviceName)

        var maxContentW = nameW
        val subPaint = Paint().apply {
            color = Color.argb(200, 255, 255, 255)
            textSize = subTextSize
            typeface = getMontserratTypeface(context)
            textAlign = Paint.Align.LEFT
        }

        // Build content lines
        val lines = mutableListOf<Pair<String, Paint>>()
        lines.add(deviceName to namePaint)
        if (hasParams) {
            val paramStr = buildParamString(info)
            val pw = subPaint.measureText(paramStr)
            if (pw > maxContentW) maxContentW = pw
            lines.add(paramStr to subPaint)
        }
        if (dateStr.isNotBlank()) {
            val dw = subPaint.measureText(dateStr)
            if (dw > maxContentW) maxContentW = dw
            lines.add(dateStr to subPaint)
        }

        // Chip background dimensions
        val chipPadH = labelTextSize * 0.55f
        val chipPadV = labelTextSize * 0.42f
        val lineH = labelTextSize * 1.25f
        val chipW = maxContentW + chipPadH * 2
        val chipH = lines.size * lineH + chipPadV * 2
        val cornerR = labelTextSize * 0.45f

        // Position: top-right
        val chipLeft = srcW - chipW - pad
        val chipTop = pad
        val chipRight = chipLeft + chipW
        val chipBottom = chipTop + chipH

        // Tail triangle pointing down-left
        val tailSize = labelTextSize * 0.35f
        val tailPath = Path().apply {
            moveTo(chipRight - cornerR, chipBottom)
            lineTo(chipRight - cornerR - tailSize, chipBottom + tailSize)
            lineTo(chipRight - cornerR - tailSize * 2f, chipBottom)
            close()
        }

        // Draw chip background
        val chipBg = Color.argb(200, Color.red(colorScheme.accent), Color.green(colorScheme.accent), Color.blue(colorScheme.accent))
        val bgLum = SmartColorExtractor.luminance(chipBg)
        // Ensure readable label: blend accent with black for dark accent, with white for light accent
        val finalBg = if (bgLum > 0.45f) {
            // Light accent: blend toward dark for white text readability
            blendWithAccentMode2(chipBg, Color.BLACK, 0.5f)
        } else {
            chipBg
        }

        val bgPaint = Paint().apply {
            color = finalBg
            isAntiAlias = true
        }
        val chipRect = RectF(chipLeft, chipTop, chipRight, chipBottom)
        canvas.drawRoundRect(chipRect, cornerR, cornerR, bgPaint)
        canvas.drawPath(tailPath, bgPaint)

        // Draw text lines
        var lineY = chipTop + chipPadV + labelTextSize * 0.82f
        for ((text, paint) in lines) {
            canvas.drawText(text, chipLeft + chipPadH, lineY, paint)
            lineY += lineH
        }
    }

    // ==================== Mode 7: Signature Strip ====================

    private fun drawSignatureStripMode(canvas: Canvas, source: Bitmap, srcW: Int, srcH: Int, spec: WatermarkMath.LayoutSpec, info: WatermarkInfo, colorScheme: SmartColorExtractor.ColorScheme, context: Context) {
        canvas.drawBitmap(source, 0f, 0f, null)

        val stripH = minOf(srcW, srcH) * 0.055f
        val pad = stripH * 0.20f

        // Semi-transparent dark background strip at top
        val bgPaint = Paint().apply {
            color = Color.argb(160, 0, 0, 0)
            isAntiAlias = true
        }
        canvas.drawRoundRect(RectF(0f, 0f, srcW.toFloat(), stripH), 0f, 0f, bgPaint)

        val deviceName = info.deviceName
            .replace("motorola ", "", ignoreCase = true)
            .replace("Motorola ", "", ignoreCase = true)
            .trim()

        // Motorola logo
        val logo = getMotorolaLogo(context)
        if (logo != null && !logo.isRecycled) {
            val logoH = stripH * 0.65f
            val logoW = (logo.width.toFloat() / logo.height) * logoH
            val logoX = pad
            val logoY = (stripH - logoH) / 2f
            val tinted = tintBitmap(logo, Color.argb(230, 255, 255, 255))
            canvas.drawBitmap(tinted, null, RectF(logoX, logoY, logoX + logoW, logoY + logoH), null)
            tinted.recycle()

            // Device name next to logo
            if (deviceName.isNotEmpty()) {
                val namePaint = Paint().apply {
                    color = Color.argb(230, 255, 255, 255)
                    textSize = stripH * 0.38f
                    typeface = Typeface.create(getMontserratTypeface(context), Typeface.BOLD)
                    textAlign = Paint.Align.LEFT
                    isFakeBoldText = true
                }
                val nameBaseline = logoY + logoH * 0.82f
                val nameX = logoX + logoW + stripH * 0.20f
                canvas.drawText(deviceName, nameX, nameBaseline, namePaint)
            }
        }

        // Params right-aligned
        if (info.showParams) {
            val paramStr = buildParamString(info)
            val paramPaint = Paint().apply {
                color = Color.argb(180, 255, 255, 255)
                textSize = stripH * 0.30f
                typeface = getMontserratTypeface(context)
                textAlign = Paint.Align.RIGHT
            }
            val baseline = stripH / 2f + paramPaint.textSize * 0.38f
            canvas.drawText(paramStr, srcW - pad, baseline, paramPaint)
        }
    }

    private fun drawBadgeMode(canvas: Canvas, source: Bitmap, srcW: Int, srcH: Int, spec: WatermarkMath.LayoutSpec, info: WatermarkInfo, colorScheme: SmartColorExtractor.ColorScheme, context: Context) {
        canvas.drawBitmap(source, 0f, 0f, null)

        val deviceName = info.deviceName
            .replace("motorola ", "", ignoreCase = true)
            .replace("Motorola ", "", ignoreCase = true)
            .trim()
        if (deviceName.isBlank()) return

        val sizeRef = minOf(srcW, srcH)
        val badgeH = sizeRef * 0.055f
        val logo = getMotorolaLogo(context)
        var logoW = 0f
        if (logo != null && !logo.isRecycled) {
            logoW = (logo.width.toFloat() / logo.height) * badgeH * 0.60f
        }

        val namePaint = Paint().apply {
            color = Color.WHITE
            textSize = badgeH * 0.38f
            typeface = Typeface.create(getMontserratTypeface(context), Typeface.BOLD)
            textAlign = Paint.Align.LEFT
            isFakeBoldText = true
        }
        val nameW = namePaint.measureText(deviceName)
        val padH = badgeH * 0.28f
        val gap = badgeH * 0.15f
        val badgeW = (if (logoW > 0) logoW + gap else 0f) + nameW + padH * 2f

        // Accent color from scheme, blend if too light
        val accent = colorScheme.accent
        val lum = SmartColorExtractor.luminance(accent)
        val finalBg = if (lum > 0.50f) {
            blendWithAccentMode2(accent, Color.BLACK, 0.35f)
        } else {
            Color.argb(200, Color.red(accent), Color.green(accent), Color.blue(accent))
        }

        val margin = sizeRef * 0.04f
        val badgeLeft = srcW - badgeW - margin
        val badgeTop = srcH - badgeH - margin

        val bgPaint = Paint().apply {
            color = finalBg
            isAntiAlias = true
        }
        canvas.drawRoundRect(
            RectF(badgeLeft, badgeTop, badgeLeft + badgeW, badgeTop + badgeH),
            badgeH / 2f, badgeH / 2f, bgPaint
        )

        // Drop shadow
        val shadowPaint = Paint().apply {
            color = Color.argb(60, 0, 0, 0)
            isAntiAlias = true
        }
        canvas.drawRoundRect(
            RectF(badgeLeft + 1f, badgeTop + 2f, badgeLeft + badgeW + 1f, badgeTop + badgeH + 2f),
            badgeH / 2f, badgeH / 2f, shadowPaint
        )
        canvas.drawRoundRect(
            RectF(badgeLeft, badgeTop, badgeLeft + badgeW, badgeTop + badgeH),
            badgeH / 2f, badgeH / 2f, bgPaint
        )

        val badgeCenterY = badgeTop + badgeH / 2f

        var cx = badgeLeft + padH
        // Logo
        if (logo != null && !logo.isRecycled) {
            val logoH = badgeH * 0.60f
            val lw = (logo.width.toFloat() / logo.height) * logoH
            val ly = badgeCenterY - logoH / 2f
            val tinted = tintBitmap(logo, Color.argb(230, 255, 255, 255))
            canvas.drawBitmap(tinted, null, RectF(cx, ly, cx + lw, ly + logoH), null)
            tinted.recycle()
            cx += lw + gap
        }

        // Name
        val nameBaseline = badgeCenterY + namePaint.textSize * 0.38f
        canvas.drawText(deviceName, cx, nameBaseline, namePaint)
    }

    // ==================== Mode 9: Cinematic Frame ====================
    // 高级电影感画框水印，仿 Leica/Hasselblad 风格
    // 白细线画框 + 左下角品牌+型号 + 右下角参数

    private fun drawCinematicFrameMode(canvas: Canvas, source: Bitmap, srcW: Int, srcH: Int, spec: WatermarkMath.LayoutSpec, info: WatermarkInfo, colorScheme: SmartColorExtractor.ColorScheme, context: Context) {
        canvas.drawBitmap(source, 0f, 0f, null)

        val isPortrait = srcH > srcW
        val refSize = minOf(srcW, srcH)

        // 白细线画框，距边缘一段优雅距离
        val frameMargin = refSize * 0.025f
        val framePaint = Paint().apply {
            color = Color.argb(220, 255, 255, 255)
            strokeWidth = refSize * 0.0022f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        canvas.drawRect(
            frameMargin, frameMargin,
            srcW - frameMargin, srcH - frameMargin,
            framePaint
        )

        // 内层更细的线（形成双线框）
        val innerMargin = frameMargin + refSize * 0.012f
        val innerFramePaint = Paint().apply {
            color = Color.argb(120, 255, 255, 255)
            strokeWidth = refSize * 0.001f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        canvas.drawRect(
            innerMargin, innerMargin,
            srcW - innerMargin, srcH - innerMargin,
            innerFramePaint
        )

        // 右下角——纯白文字：机型 + 参数，左对齐
        val textColor = Color.argb(230, 255, 255, 255)
        val mutedColor = Color.argb(170, 255, 255, 255)
        val nameSize = refSize * 0.022f
        val paramSize = refSize * 0.017f
        val bottomPad = refSize * 0.04f
        val marginX = srcW - refSize * 0.04f
        val baselineY = srcH - bottomPad

        val deviceName = info.deviceName
            .replace("motorola ", "", ignoreCase = true)
            .replace("Motorola ", "", ignoreCase = true)
            .trim()

        // 机型名（加粗）右下
        val namePaint = Paint().apply {
            color = textColor
            textSize = nameSize
            typeface = Typeface.create(getMontserratTypeface(context), Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
            isFakeBoldText = true
            setShadowLayer(nameSize * 0.15f, 0f, nameSize * 0.08f, Color.argb(100, 0, 0, 0))
        }
        canvas.drawText(deviceName, marginX, baselineY, namePaint)

        // 参数行（在机型上方）
        if (info.showParams) {
            val paramPaint = Paint().apply {
                color = mutedColor
                textSize = paramSize
                typeface = getMontserratTypeface(context)
                textAlign = Paint.Align.RIGHT
                setShadowLayer(paramSize * 0.15f, 0f, paramSize * 0.08f, Color.argb(100, 0, 0, 0))
            }
            canvas.drawText(buildParamString(info), marginX, baselineY - nameSize * 1.25f, paramPaint)
        }

        // 日期（参数上方，极小字）
        if (info.showDate) {
            val dateStr = info.date.replaceFirst(Regex("\\s+\\d{1,2}:\\d{2}(:\\d{2})?\\s*[apAP][mM]"), "")
            val datePaint = Paint().apply {
                color = Color.argb(120, 255, 255, 255)
                textSize = paramSize * 0.7f
                typeface = getMontserratTypeface(context)
                textAlign = Paint.Align.RIGHT
                setShadowLayer(paramSize * 0.12f, 0f, paramSize * 0.06f, Color.argb(100, 0, 0, 0))
            }
            val dateBaseY = if (info.showParams) baselineY - nameSize * 1.25f - paramSize * 1.3f
                             else baselineY - nameSize * 1.25f
            canvas.drawText(dateStr, marginX, dateBaseY, datePaint)
        }

        // 左下角——小写 motorola logo + 文字
        val logo = getMotorolaLogo(context)
        if (logo != null && !logo.isRecycled) {
            val logoH = nameSize * 0.95f
            val logoW = (logo.width.toFloat() / logo.height) * logoH
            val logoX = refSize * 0.04f
            val logoY = baselineY - nameSize * 0.95f
            val tinted = tintBitmap(logo, Color.argb(180, 255, 255, 255))
            canvas.drawBitmap(tinted, null, RectF(logoX, logoY, logoX + logoW, logoY + logoH), null)
            tinted.recycle()
        }
    }

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
        val outputH = if (info.style == 1 || info.style == 4) h + spec.barH.toInt() else h
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

        val extend = info.style == 1 || info.style == 4
        when (info.style) {
            2 -> drawBlurFrameMode(canvas, source, w, outputH, spec, info, colorScheme, context)
            3 -> drawDiagonalMode(canvas, source, w, outputH, spec, info, colorScheme, context)
            4 -> drawBarMode(canvas, source, w, outputH, spec, info, colorScheme, context, extend)
            5 -> drawFilmFrameMode(canvas, source, w, outputH, spec, info, colorScheme, context)
            6 -> drawMinimalistLabelMode(canvas, source, w, outputH, spec, info, colorScheme, context)
            7 -> drawSignatureStripMode(canvas, source, w, outputH, spec, info, colorScheme, context)
            8 -> drawBadgeMode(canvas, source, w, outputH, spec, info, colorScheme, context)
            9 -> drawCinematicFrameMode(canvas, source, w, outputH, spec, info, colorScheme, context)
            10 -> drawTransparencyCenterMode(canvas, source, w, outputH, spec, info, colorScheme, context)
            else -> drawBarMode(canvas, source, w, outputH, spec, info, colorScheme, context, extend)
        }

        return output
    }
}
