package com.example.associations

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.associations.data.GameStorage
import com.example.associations.game.GameViewModel
import com.example.associations.ui.GameScreen
import com.example.associations.ui.HowToPlayScreen
import com.example.associations.ui.MenuScreen
import com.example.associations.ui.SettingsScreen
import com.example.associations.ui.collectAsStateValue
import com.example.associations.ui.theme.AssociationsTheme

private enum class Screen { MENU, GAME, HOWTO, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
private fun App() {
    val vm: GameViewModel = viewModel()
    val settings by vm.settings.collectAsStateValue()
    val level by vm.level.collectAsStateValue()

    val context = LocalContext.current
    val storage = remember { GameStorage(context) }

    var screen by remember { mutableStateOf(Screen.MENU) }
    var hasSaved by remember { mutableStateOf(false) }
    var savedRefresh by remember { mutableStateOf(0) }

    // Обновляем флаг «есть сохранённая партия» при возврате в меню.
    LaunchedEffect(savedRefresh) {
        hasSaved = storage.hasSavedGame()
    }

    // Сохраняем партию при сворачивании приложения.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) vm.onPause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AssociationsTheme(darkTheme = settings.darkTheme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (screen) {
                Screen.MENU -> MenuScreen(
                    level = level,
                    hasSavedGame = hasSaved,
                    onContinue = { vm.startOrResume(); screen = Screen.GAME },
                    onPlay = { vm.newGame(); screen = Screen.GAME },
                    onHowTo = { screen = Screen.HOWTO },
                    onSettings = { screen = Screen.SETTINGS }
                )
                Screen.GAME -> GameScreen(
                    vm = vm,
                    onMenu = {
                        vm.onPause()
                        savedRefresh++
                        screen = Screen.MENU
                    }
                )
                Screen.HOWTO -> HowToPlayScreen(onBack = { screen = Screen.MENU })
                Screen.SETTINGS -> SettingsScreen(
                    soundEnabled = settings.soundEnabled,
                    darkTheme = settings.darkTheme,
                    onSoundChange = vm::setSound,
                    onDarkChange = vm::setDark,
                    onBack = { screen = Screen.MENU }
                )
            }
        }
    }
}
