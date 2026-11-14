package com.lampplayer.tv.center

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class CenterThemeTest {
    @Test fun smallTextIsRaisedToTheTvMinimumAndScaled() {
        assertEquals(13f, CenterTheme.textSize(11, 1f), 0.01f)
        assertEquals(16f, CenterTheme.textSize(16, 1f), 0.01f)
        assertEquals(13f * 1.3f, CenterTheme.textSize(12, CenterTheme.scales.getValue("xlarge")), 0.01f)
    }
    @Test fun unknownSettingsFallBackToTheDefaultTheme() {
        val theme = CenterTheme.of("missing", "missing")
        assertEquals(CenterTheme.default.panel, theme.panel)
        assertEquals(1f, theme.textScale, 0f)
        assertNotEquals(CenterTheme.default.accent, CenterTheme.of("contrast", "normal").accent)
    }
}
