package com.lampplayer.tv.center

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.InputEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28],qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MediaCenterUiTest {
    class Host : MediaCenterActivity() {
        override fun loadHome() { showHome((1..8).map {
            CenterTitle(it, if(it%2==0) "tv" else "movie", listOf("Тихий город","За горизонтом","Новый день","На орбите")[(it-1)%4],
                overview="Истории, к которым хочется возвращаться. Каталог, серии и просмотр в одном приложении.", year="2026",rating="8.2")
        }) }
    }
    private fun all(view: View): List<View> = listOf(view) + if(view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    @Test fun homeAndSearchRenderAndRemoteCanLeaveTheNavigation() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
        val controller=Robolectric.buildActivity(Host::class.java).setup().visible().windowFocusChanged(true)
        try {
            val host=controller.get();val root=host.findViewById<View>(android.R.id.content)
            fun frame() {
                root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY))
                root.layout(0,0,960,540);shadowOf(Looper.getMainLooper()).idleFor(200,TimeUnit.MILLISECONDS)
            }
            fun screenshot(name:String) {
                val bitmap=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888); root.draw(Canvas(bitmap))
                val file=File("build/ui-preview/$name.png");file.parentFile!!.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
            frame()
            val home=all(root).filterIsInstance<TextView>().first { it.text.toString()=="Главная" }
            home.requestFocus();frame()
            val viewRoot=root.rootView.parent!!
            for(action in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)) {
                ReflectionHelpers.callInstanceMethod<Any?>(viewRoot,"dispatchInputEvent",
                    ReflectionHelpers.ClassParameter.from(InputEvent::class.java,KeyEvent(action,KeyEvent.KEYCODE_DPAD_RIGHT)))
            }
            frame();assertFalse(home.hasFocus());assertNotNull(root.findFocus());screenshot("media-center-home")
            all(root).filterIsInstance<TextView>().first { it.text.toString()=="Поиск" }.performClick();frame()
            assertTrue(all(root).filterIsInstance<TextView>().any { it.text.toString()=="Найти фильм или сериал" })
            screenshot("media-center-search")
        } finally { controller.pause().stop().destroy() }
    }
}
