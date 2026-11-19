package com.watermark.photo.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.google.android.material.chip.ChipGroup
import com.watermark.photo.R
import com.watermark.photo.data.SettingsPrefs
import com.watermark.photo.databinding.ActivitySettingsBinding

/**
 * v1.3.4: 设置页（M3 Expressive）
 * - 主题：跟随系统 / 浅色 / 深色
 * - 语言：跟随系统 / English / 简体中文
 * - 关于：版本、作者、GitHub 仓库
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // [v1.3.6] 内容延伸到透明状态栏下，给 AppBarLayout 动态加顶部 padding = 状态栏高度，
        // 让工具栏稳定落在状态栏下方，不再与图标重叠（冷启动也可靠）。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val isLight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = isLight
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBar) { v, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = statusTop)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 版本号（不依赖 BuildConfig，用 PackageManager 读取）
        val versionName = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
        } catch (_: PackageManager.NameNotFoundException) { "-" }
        binding.tvVersion.text = getString(R.string.about_version, versionName)

        setupThemeSelector()
        setupLanguageSelector()
        setupAbout()
    }

    private fun setupThemeSelector() {
        binding.themeGroup.check(
            when (SettingsPrefs.getTheme(this)) {
                SettingsPrefs.THEME_LIGHT -> R.id.chipThemeLight
                SettingsPrefs.THEME_DARK -> R.id.chipThemeDark
                else -> R.id.chipThemeSystem
            }
        )
        binding.themeGroup.setOnCheckedStateChangeListener { _: ChipGroup, _: List<Int> ->
            val mode = when (binding.themeGroup.checkedChipId) {
                R.id.chipThemeLight -> SettingsPrefs.THEME_LIGHT
                R.id.chipThemeDark -> SettingsPrefs.THEME_DARK
                else -> SettingsPrefs.THEME_SYSTEM
            }
            SettingsPrefs.setTheme(this, mode)
        }
    }

    private fun setupLanguageSelector() {
        binding.languageGroup.check(
            when (SettingsPrefs.getLang(this)) {
                SettingsPrefs.LANG_EN -> R.id.chipLangEn
                SettingsPrefs.LANG_ZH -> R.id.chipLangZh
                else -> R.id.chipLangSystem
            }
        )
        binding.languageGroup.setOnCheckedStateChangeListener { _: ChipGroup, _: List<Int> ->
            val tag = when (binding.languageGroup.checkedChipId) {
                R.id.chipLangEn -> SettingsPrefs.LANG_EN
                R.id.chipLangZh -> SettingsPrefs.LANG_ZH
                else -> SettingsPrefs.LANG_SYSTEM
            }
            SettingsPrefs.setLang(this, tag)
        }
    }

    private fun setupAbout() {
        binding.rowGithub.setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/stephenywl82-eng/MotoWatermark"))
                )
            } catch (_: Exception) { }
        }
    }
}
