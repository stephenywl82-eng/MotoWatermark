package com.watermark.photo

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.watermark.photo.data.DeviceMapper
import com.watermark.photo.data.SettingsPrefs

class MotoWatermarkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // [v1.3.4] 恢复已保存的主题/语言设置（须在 Activity 创建前应用）
        SettingsPrefs.applySaved(this)
        // [v1.1.6] 启用 Material You 动态取色：强调色跟随系统主题色（不再固定蓝）。
        // 若设备不支持动态取色，则回退到 colors.xml 中定义的静态色板。
        DynamicColors.applyToActivitiesIfAvailable(this)
        // [v1.1.7] 初始化机型库（内置 JSON + 用户导入覆盖）
        DeviceMapper.init(this)
    }
}
