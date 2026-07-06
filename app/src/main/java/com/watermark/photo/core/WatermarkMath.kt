package com.watermark.photo.core

import android.graphics.*

/**
 * 水印排版数学公式
 * 所有尺寸基于短边 S = min(W, H) 的百分比计算
 */
object WatermarkMath {
    data class LayoutSpec(
        val barH: Float,           // 底栏高度 = S × 12%
        val padH: Float,           // 水平内边距 = barH × 0.5
        val padV: Float,           // 垂直内边距 = barH × 0.25
        val logoMaxH: Float,       // Logo 最大高度 = barH × 0.7
        val logoTextGap: Float,    // Logo 与文字间距 = barH × 0.15
        val modelTextSize: Float,  // 机型/品牌文字大小 = barH × 30%
        val paramTextSize: Float,  // 参数文字大小 = barH × 24%
        val dateTextSize: Float,   // 日期文字大小 = barH × 18%
        val lineGap: Float,        // 参数与日期行间距 = barH × 12%
        val cornerRadius: Float,   // 圆角半径 = barH × 0.25
    )

    fun computeLayoutSpec(w: Int, h: Int): LayoutSpec {
        val s = minOf(w, h).toFloat()
        val barH = s * 0.13f
        return LayoutSpec(
            barH = barH,
            padH = barH * 0.5f,
            padV = barH * 0.25f,
            logoMaxH = barH * 0.7f,
            logoTextGap = barH * 0.15f,
            modelTextSize = barH * 0.30f,
            paramTextSize = barH * 0.24f,
            dateTextSize = barH * 0.18f,
            lineGap = barH * 0.12f,
            cornerRadius = barH * 0.25f,
        )
    }
}