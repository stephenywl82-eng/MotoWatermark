package com.watermark.photo.data

import android.graphics.Bitmap

/**
 * 水印信息数据类
 *
 * @param brandLogo   Moto 圆形 Logo（最左）
 * @param rightLogo   FIFA 合作徽章（最右）
 * @param focalLength 焦距，如 "24mm"
 * @param aperture    光圈，如 "f/1.8"
 * @param shutterSpeed 快门，如 "1/200s"
 * @param iso         ISO，如 "ISO388"
 * @param date        日期，如 "22 Jan 2026 4:04 pm"
 * @param deviceModel 机型名，从 EXIF 读取
 */
data class WatermarkInfo(
    val brandLogo: Bitmap?,
    val rightLogo: Bitmap?,
    val deviceName: String,
    val focalLength: String,
    val aperture: String,
    val shutterSpeed: String,
    val iso: String,
    val date: String,
    val deviceModel: String = "",
    val isMotorola: Boolean = true,
    val showParams: Boolean = true,   // 是否显示参数行（焦距/光圈/ISO）
    val showDate: Boolean = true,     // 是否显示日期行
    val style: Int = 1,               // 水印样式（1=模式1）
    val richSaturation: Boolean = false,  // 高饱和度模式（浓郁版 vs 莫兰迪淡雅版）
    val originalColor: Boolean = false,    // 原色模式（高保真色相 + 压制饱和度）
    val barbiePink: Boolean = false,       // 浓郁芭比粉模式（强制 L=0.6，S=max(seed,0.95)）
    val manualPickColor: Int? = null,  // 手动取色值（非 null 时覆盖自动取色，仅模式1）
    val useWhiteText: Boolean? = null,   // 强制文字颜色：true=白色, false=黑色, null=自动
    val useWhiteBg: Boolean = false,       // 模式1纯白底色开关
    val showLogo: Boolean = true,          // 是否显示左侧 Motorola logo
    val ownerName: String = ""             // 照片拥有者名字，如 @stephen
)

enum class DisplayMode {
    SIGNATURE,   // motorola + Signature 手写体
    MODEL_NAME, // motorola + 真实机型名
}