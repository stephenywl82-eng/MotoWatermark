package com.watermark.photo.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 从图片 EXIF 读取真实拍摄参数
 */
object ExifParamsExtractor {

    data class RealParams(
        val model: String,         // "edge 50 ultra"
        val focalLength: String,   // "24mm"
        val aperture: String,      // "f/1.8"
        val shutterSpeed: String,  // "1/200s"
        val iso: String,           // "ISO388"
        val dateTaken: String,     // "22 Jan 2026 4:04 pm"
        val isMotorola: Boolean = true  // 是否摩托手机
    )

    /** 从 Bitmap + InputStream 提取 EXIF 参数 */
    fun extract(bitmap: Bitmap, inputStreamProvider: () -> InputStream?): RealParams {
        return try {
            inputStreamProvider()?.use { stream ->
                val exif = ExifInterface(stream)

                val model = extractModel(exif)
                val focalLength = formatFocalLength(exif)
                val aperture = formatAperture(exif)
                val shutterSpeed = formatShutterSpeed(exif)
                val iso = formatIso(exif)
                val dateTaken = extractDate(exif)
                val isMoto = isMotorolaPhone(exif)

                RealParams(model, focalLength, aperture, shutterSpeed, iso, dateTaken, isMoto)
            } ?: fallback()
        } catch (e: Exception) {
            fallback()
        }
    }

    private fun extractModel(exif: ExifInterface): String {
        val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: ""
        val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: ""

        // 非摩托手机返回空
        val isMoto = make.contains("motorola", ignoreCase = true) || 
                     model.contains("motorola", ignoreCase = true)
        if (!isMoto) return ""

        return DeviceMapper.getDisplayName(model)
    }

    /** 判断是否为摩托手机 */
    private fun isMotorolaPhone(exif: ExifInterface): Boolean {
        val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: ""
        val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: ""
        return make.contains("motorola", ignoreCase = true) || 
               model.contains("motorola", ignoreCase = true)
    }

    private fun formatFocalLength(exif: ExifInterface): String {
        // 优先尝试读取35mm等效焦距
        val focal35mm = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, -1)
        if (focal35mm > 0) {
            return "${focal35mm}mm"
        }

        // 读取物理焦距，通过crop factor换算
        val fl = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
        if (fl <= 0) return ""

        // 获取传感器尺寸计算crop factor
        val sensorWidth = exif.getAttributeDouble(ExifInterface.TAG_PIXEL_X_DIMENSION, 0.0)
        val sensorHeight = exif.getAttributeDouble(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0.0)

        val cropFactor = when {
            sensorWidth > 0 && sensorHeight > 0 -> {
                // 计算传感器对角线 (单位mm, 假设像素大小1.4um)
                val sensorDiag = kotlin.math.sqrt(sensorWidth * sensorWidth + sensorHeight * sensorHeight) * 0.0014
                (43.27 / sensorDiag).coerceIn(3.5, 8.0)
            }
            fl < 3.0 -> 6.5  // 超广角
            fl < 4.0 -> 5.5  // 广角
            fl < 6.0 -> 4.1   // 主摄 (1/1.55")
            fl < 8.0 -> 3.5   // 中焦
            else -> 2.8       // 长焦
        }

        // 计算35mm等效焦距
        val equiv35mm = (fl * cropFactor).coerceIn(12.0, 200.0)


        // 转换为标准焦距：13、23、35、50、70、85、135
        val standard = when {
            equiv35mm <= 18 -> 13
            equiv35mm <= 28 -> 23
            equiv35mm <= 42 -> 35
            equiv35mm <= 60 -> 50
            equiv35mm <= 78 -> 70
            equiv35mm <= 105 -> 85
            equiv35mm <= 160 -> 135
            else -> 200
        }
        return "${standard}mm"
    }

    private fun formatAperture(exif: ExifInterface): String {
        val av = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0)
        if (av < 0) return ""
        return "f/${String.format(Locale.US, "%.1f", av)}"
    }

    private fun formatShutterSpeed(exif: ExifInterface): String {
        val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0)
        return when {
            exposure < 0 -> ""
            exposure >= 1.0 -> "${exposure.toInt()}s"
            else -> {
                val denominator = (1.0 / exposure).toInt()
                "1/${denominator}s"
            }
        }
    }

    private fun formatIso(exif: ExifInterface): String {
        val isoVal = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)
        return if (isoVal > 0) "ISO$isoVal" else ""
    }

    private fun extractDate(exif: ExifInterface): String {
        val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return ""

        return try {
            val inputFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            val outputFormat = SimpleDateFormat("dd MMM yyyy h:mm a", Locale.US)
            val date = inputFormat.parse(dateStr) ?: return ""
            outputFormat.format(date)
        } catch (e: Exception) {
            ""
        }
    }

    private fun fallback(): RealParams {
        return RealParams(
            model = "",
            focalLength = "",
            aperture = "",
            shutterSpeed = "",
            iso = "",
            dateTaken = "",
            isMotorola = false
        )
    }
}