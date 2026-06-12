package com.lampplayer.tv.settings

import android.os.Bundle
import android.view.KeyEvent
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.lampplayer.tv.data.datastore.AppSettings
import com.lampplayer.tv.data.datastore.SettingsDataStore
import com.lampplayer.tv.databinding.ActivitySettingsBinding
import com.lampplayer.tv.domain.model.BufferProfileType
import com.lampplayer.tv.engine.EngineType
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SettingsDataStore,
) : ViewModel() {
    val settings = store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun setEngine(v: String) = viewModelScope.launch { store.setEngine(v) }
    fun setBuffer(v: BufferProfileType) = viewModelScope.launch { store.setBuffer(v) }
    fun setAutonext(v: Boolean) = viewModelScope.launch { store.setAutonext(v) }
    fun setAutonextDelay(v: Int) = viewModelScope.launch { store.setAutonextDelay(v) }
    fun setRememberTracks(v: Boolean) = viewModelScope.launch { store.setRememberTracks(v) }
    fun setSkipIntro(v: Boolean) = viewModelScope.launch { store.setSkipIntro(v) }
    fun setDiag(v: Boolean) = viewModelScope.launch { store.setDiag(v) }
    fun setAfr(v: Boolean) = viewModelScope.launch { store.setAfr(v) }
    fun setSleepTimer(min: Int) = viewModelScope.launch { store.setSleepTimer(min) }
    fun setOsdTimeout(sec: Int) = viewModelScope.launch { store.setOsdTimeout(sec) }
}

private val SLEEP_OPTIONS = listOf(0, 15, 30, 45, 60, 90)
private val OSD_OPTIONS = listOf(5, 10, 15, 30, 60)

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val vm: SettingsViewModel by viewModels()
    private var ignoreSpinnerEvent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSpinners()
        setupSwitches()

        lifecycleScope.launch {
            vm.settings.collect { s -> applySettings(s) }
        }

        binding.btnClose.setOnClickListener { finish() }

        binding.tvUpdateStatus.text = "Версия ${com.lampplayer.tv.BuildConfig.VERSION_NAME}"
        binding.btnUpdate.setOnClickListener { checkAndUpdate() }
    }

    private fun checkAndUpdate() {
        binding.btnUpdate.isEnabled = false
        binding.tvUpdateStatus.text = "Проверка…"
        lifecycleScope.launch {
            val info = com.lampplayer.tv.update.UpdateManager.check(com.lampplayer.tv.BuildConfig.VERSION_CODE)
            if (info == null) {
                binding.tvUpdateStatus.text = "Установлена последняя версия (${com.lampplayer.tv.BuildConfig.VERSION_NAME})"
                binding.btnUpdate.isEnabled = true
                return@launch
            }
            binding.tvUpdateStatus.text = "Загрузка ${info.versionName}…"
            val file = com.lampplayer.tv.update.UpdateManager.download(this@SettingsActivity, info.url, info.sha256)
            if (file == null) {
                binding.tvUpdateStatus.text = "Не удалось скачать обновление (или файл не прошёл проверку)"
                binding.btnUpdate.isEnabled = true
                return@launch
            }
            binding.tvUpdateStatus.text = "Установка ${info.versionName}…"
            binding.btnUpdate.isEnabled = true
            com.lampplayer.tv.update.UpdateManager.install(this@SettingsActivity, file)
        }
    }

    private fun setupSpinners() {
        val engineAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf("Авто (ExoPlayer + libVLC)", "Только ExoPlayer", "Только libVLC"),
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerEngine.adapter = engineAdapter
        binding.spinnerEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (ignoreSpinnerEvent) return
                vm.setEngine(EngineType.ALL[pos.coerceIn(0, EngineType.ALL.size - 1)])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val bufferAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("LOW — Слабые боксы", "MEDIUM — Стандарт", "HIGH — Shield TV"))
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerBuffer.adapter = bufferAdapter
        binding.spinnerBuffer.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (ignoreSpinnerEvent) return
                vm.setBuffer(BufferProfileType.entries[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val delayAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, (5..30 step 5).map { "${it}с" })
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerDelay.adapter = delayAdapter
        binding.spinnerDelay.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (ignoreSpinnerEvent) return
                vm.setAutonextDelay((pos + 1) * 5)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val sleepAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            SLEEP_OPTIONS.map { if (it == 0) "Выкл" else "$it мин" },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerSleep.adapter = sleepAdapter
        binding.spinnerSleep.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (ignoreSpinnerEvent) return
                vm.setSleepTimer(SLEEP_OPTIONS[pos.coerceIn(0, SLEEP_OPTIONS.size - 1)])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val osdAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, OSD_OPTIONS.map { "$it с" },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerOsdTimeout.adapter = osdAdapter
        binding.spinnerOsdTimeout.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                if (ignoreSpinnerEvent) return
                vm.setOsdTimeout(OSD_OPTIONS[pos.coerceIn(0, OSD_OPTIONS.size - 1)])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupSwitches() {
        binding.switchAutonext.setOnCheckedChangeListener { _, v -> if (!ignoreSpinnerEvent) vm.setAutonext(v) }
        binding.switchRememberTracks.setOnCheckedChangeListener { _, v -> if (!ignoreSpinnerEvent) vm.setRememberTracks(v) }
        binding.switchSkipIntro.setOnCheckedChangeListener { _, v -> if (!ignoreSpinnerEvent) vm.setSkipIntro(v) }
        binding.switchDiag.setOnCheckedChangeListener { _, v -> if (!ignoreSpinnerEvent) vm.setDiag(v) }
        binding.switchAfr.setOnCheckedChangeListener { _, v -> if (!ignoreSpinnerEvent) vm.setAfr(v) }
    }

    private fun applySettings(s: AppSettings) {
        ignoreSpinnerEvent = true
        binding.spinnerEngine.setSelection(EngineType.ALL.indexOf(EngineType.normalize(s.engine)).coerceAtLeast(0))
        binding.spinnerBuffer.setSelection(s.buffer.ordinal)
        val delayIndex = ((s.autonextDelay / 5) - 1).coerceIn(0, 5)
        binding.spinnerDelay.setSelection(delayIndex)
        binding.switchAutonext.isChecked = s.autonext
        binding.switchRememberTracks.isChecked = s.rememberTracks
        binding.switchSkipIntro.isChecked = s.skipIntro
        binding.switchDiag.isChecked = s.diag
        binding.switchAfr.isChecked = s.afr
        binding.spinnerSleep.setSelection(SLEEP_OPTIONS.indexOf(s.sleepTimerMin).coerceAtLeast(0))
        binding.spinnerOsdTimeout.setSelection(OSD_OPTIONS.indexOf(s.osdTimeoutSec).coerceAtLeast(0))
        binding.spinnerDelay.isEnabled = s.autonext
        ignoreSpinnerEvent = false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
