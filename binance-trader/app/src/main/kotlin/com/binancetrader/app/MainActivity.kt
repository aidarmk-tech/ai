package com.binancetrader.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.binancetrader.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val intervals = listOf("5m", "15m", "30m", "1h", "4h")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        Prefs.load(this)
        b.editApiKey.setText(Prefs.apiKey)
        b.editApiSecret.setText(Prefs.apiSecret)
        b.editSymbol.setText(Prefs.getString(this, "symbol", "BTCUSDT"))
        b.editPct.setText(Prefs.getString(this, "positionPct", "20"))
        b.switchTestnet.isChecked = Prefs.getBoolean(this, "testnet", true)

        b.spinnerInterval.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, intervals)
        val savedInterval = Prefs.getString(this, "interval", "15m")
        b.spinnerInterval.setSelection(intervals.indexOf(savedInterval).coerceAtLeast(0))

        b.btnStart.setOnClickListener { onStartStop() }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BotState.status.collect { st ->
                    b.textStatus.text = st.statusLine
                    b.btnStart.text = if (st.running) "Остановить бота" else "Запустить бота"
                    setInputsEnabled(!st.running)
                    b.textLog.text = st.logLines.takeLast(120).joinToString("\n")
                    b.scrollLog.post { b.scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun setInputsEnabled(enabled: Boolean) {
        b.editApiKey.isEnabled = enabled
        b.editApiSecret.isEnabled = enabled
        b.editSymbol.isEnabled = enabled
        b.editPct.isEnabled = enabled
        b.spinnerInterval.isEnabled = enabled
        b.switchTestnet.isEnabled = enabled
    }

    private fun onStartStop() {
        if (BotState.status.value.running) {
            TradingService.stop(this)
            return
        }

        val key = b.editApiKey.text.toString().trim()
        val secret = b.editApiSecret.text.toString().trim()
        val symbol = b.editSymbol.text.toString().trim().uppercase()
        val pct = b.editPct.text.toString().trim().toDoubleOrNull()

        if (key.isEmpty() || secret.isEmpty()) {
            Toast.makeText(this, "Введите API-ключ и секрет", Toast.LENGTH_LONG).show()
            return
        }
        if (symbol.length < 5) {
            Toast.makeText(this, "Укажите торговую пару, например BTCUSDT", Toast.LENGTH_LONG).show()
            return
        }
        if (pct == null || pct < 1 || pct > 100) {
            Toast.makeText(this, "Размер позиции: число от 1 до 100 (%)", Toast.LENGTH_LONG).show()
            return
        }

        Prefs.saveKeys(this, key, secret)
        Prefs.putString(this, "symbol", symbol)
        Prefs.putString(this, "positionPct", pct.toString())
        Prefs.putString(this, "interval", intervals[b.spinnerInterval.selectedItemPosition])
        Prefs.putBoolean(this, "testnet", b.switchTestnet.isChecked)

        if (!b.switchTestnet.isChecked) {
            AlertDialog.Builder(this)
                .setTitle("Реальная торговля")
                .setMessage(
                    "Бот будет торговать НАСТОЯЩИМИ деньгами на вашем счёте Binance. " +
                        "Алгоритмическая торговля может приносить убытки. Продолжить?"
                )
                .setPositiveButton("Да, торговать") { _, _ -> TradingService.start(this) }
                .setNegativeButton("Отмена", null)
                .show()
        } else {
            TradingService.start(this)
        }
    }
}
