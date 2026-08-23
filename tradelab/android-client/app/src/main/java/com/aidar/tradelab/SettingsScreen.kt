package com.aidar.tradelab

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class SettingsScreen(
    private val activity: MainActivity,
    private val onboarding: Boolean,
    private val onConnected: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val root = ScrollView(activity)
    private val content = LinearLayout(activity)
    private lateinit var serverUrl: EditText
    private lateinit var readToken: EditText
    private lateinit var saveButton: Button
    private lateinit var feedback: TextView

    init {
        root.setBackgroundColor(Ui.BG)
        root.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 20), Ui.dp(activity, 12), Ui.dp(activity, 24))
        content.orientation = LinearLayout.VERTICAL
        root.addView(content)
        build()
    }

    fun view(): View = root

    private fun conn() = activity.getSharedPreferences("connection", Context.MODE_PRIVATE)

    private fun build() {
        content.removeAllViews()

        val title = if (onboarding) "Подключение к TradeLab" else "Настройки"
        content.addView(Ui.text(activity, title, 20, Ui.TEXT, bold = true))
        content.addView(Ui.spacer(activity, 6))
        if (onboarding) {
            content.addView(
                Ui.text(
                    activity,
                    "Введи адрес сервера и read token — они сохранятся на устройстве.",
                    14, Ui.MUTED,
                )
            )
            content.addView(Ui.spacer(activity, 14))
        }

        val card = Ui.card(activity)
        card.addView(Ui.text(activity, "Server URL", 12, Ui.MUTED, bold = true))
        serverUrl = EditText(activity).apply {
            hint = "https://…"
            setText(conn().getString("server_url", BuildConfig.SERVER_URL) ?: BuildConfig.SERVER_URL)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setTextColor(Ui.TEXT)
        }
        card.addView(serverUrl)
        card.addView(Ui.spacer(activity, 10))

        card.addView(Ui.text(activity, "Read token", 12, Ui.MUTED, bold = true))
        readToken = EditText(activity).apply {
            hint = "токен только для чтения"
            setText(conn().getString("read_token", "") ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            setTextColor(Ui.TEXT)
        }
        card.addView(readToken)
        card.addView(Ui.spacer(activity, 12))

        feedback = Ui.text(activity, "", 13, Ui.MUTED)
        saveButton = Button(activity).apply { text = if (onboarding) "Проверить и подключиться" else "Сохранить" }
        saveButton.setOnClickListener { verifyAndSave() }
        card.addView(saveButton)
        card.addView(Ui.spacer(activity, 4))
        card.addView(feedback)
        content.addView(card)

        if (!onboarding) {
            content.addView(Ui.spacer(activity, 12))
            val about = Ui.card(activity)
            about.addView(Ui.text(activity, "О приложении", 15, Ui.TEXT, bold = true))
            about.addView(Ui.spacer(activity, 6))
            about.addView(
                Ui.text(
                    activity,
                    "TradeLab client 0.3.0\nТеневой турнир R4C · snapshot sync + live dashboard",
                    13, Ui.MUTED,
                )
            )
            content.addView(about)
        }
    }

    private fun verifyAndSave() {
        val url = serverUrl.text.toString().trim().trimEnd('/')
        val token = readToken.text.toString().trim()
        if (!url.startsWith("https://")) {
            fail("Нужен HTTPS адрес сервера.")
            return
        }
        if (token.isBlank()) {
            fail("Введите read token.")
            return
        }
        // persist first so ApiClient picks them up for the check
        conn().edit().putString("server_url", url).putString("read_token", token).apply()
        saveButton.isEnabled = false
        feedback.setTextColor(Ui.MUTED)
        feedback.text = "Проверяю соединение…"

        scope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    val h = ApiClient(activity).health()
                    if (h.ok) null else IOException("сервер ответил без ok=true")
                } catch (e: Exception) {
                    e
                }
            }
            saveButton.isEnabled = true
            if (err == null) {
                feedback.setTextColor(Ui.GREEN)
                feedback.text = "Готово: подключено ✓"
                onConnected()
            } else {
                fail(friendly(err))
            }
        }
    }

    private fun friendly(e: Exception): String = when {
        e is ApiException && e.httpCode == 401 -> "Токен отклонён сервером."
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun fail(msg: String) {
        feedback.setTextColor(Ui.RED)
        feedback.text = msg
    }
}
