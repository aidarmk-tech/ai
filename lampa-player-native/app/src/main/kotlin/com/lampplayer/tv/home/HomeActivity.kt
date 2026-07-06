package com.lampplayer.tv.home

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lampplayer.tv.BuildConfig
import com.lampplayer.tv.databinding.ActivityHomeBinding
import com.lampplayer.tv.settings.SettingsActivity
import com.lampplayer.tv.update.UpdateManager
import kotlinx.coroutines.launch

/**
 * Launcher home screen shown when the app icon is opened directly (playback itself
 * is always started from Lampa). Just identity + update + settings — no fake IPTV.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvVersion.text = "Версия ${BuildConfig.VERSION_NAME}"
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnUpdate.setOnClickListener { checkUpdate() }
        binding.btnUpdate.requestFocus()
    }

    private fun checkUpdate() {
        binding.btnUpdate.isEnabled = false
        binding.tvStatus.text = "Проверка обновления…"
        lifecycleScope.launch {
            val info = UpdateManager.check(BuildConfig.VERSION_CODE)
            if (info == null) {
                binding.tvStatus.text = "Установлена последняя версия (${BuildConfig.VERSION_NAME})"
                binding.btnUpdate.isEnabled = true
                return@launch
            }
            binding.tvStatus.text = "Загрузка ${info.versionName}…"
            val file = UpdateManager.download(this@HomeActivity, info.url, info.sha256)
            binding.btnUpdate.isEnabled = true
            if (file == null) {
                binding.tvStatus.text = "Не удалось скачать обновление"
                return@launch
            }
            binding.tvStatus.text = "Установка ${info.versionName}…"
            if (!UpdateManager.install(this@HomeActivity, file))
                binding.tvStatus.text = "Обновление отклонено: подпись APK не совпадает"
        }
    }
}
