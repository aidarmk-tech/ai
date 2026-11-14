package com.lampplayer.tv.data

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.CookieHandler
import java.net.URI

class BrowserMediaSessionTest {
    @Test fun browserLookupIsRepeatedForEachHostAndDoesNotCopyHeadersAcrossRedirects() {
        val previous=CookieHandler.getDefault()
        try {
            BrowserMediaSession.refresh()
            val lookups=mutableListOf<String>()
            BrowserMediaSession.install { address ->
                lookups+=address
                when(address.toHttpUrl().host) {
                    "one.test" -> "session=first"
                    "two.test" -> "session=second"
                    else -> null
                }
            }
            val handler=CookieHandler.getDefault()
            assertEquals(listOf("session=first"),handler.get(URI("https://one.test/a"),emptyMap())["Cookie"])
            assertEquals(listOf("session=second"),handler.get(URI("https://two.test/b"),emptyMap())["Cookie"])
            assertTrue(handler.get(URI("https://unrelated.test/b"),emptyMap()).isEmpty())
            assertEquals(3,lookups.size)
            assertTrue(handler.get(URI("https://one.test/a"),mapOf("Cookie" to listOf("custom=explicit"))).isEmpty())
        } finally { BrowserMediaSession.refresh();BrowserMediaSession.install { null };CookieHandler.setDefault(previous) }
    }

    @Test fun responseCookiesKeepDomainPathAndSecureScopeAndDeletionSuppressesOldBrowserValue() {
        val previous=CookieHandler.getDefault()
        try {
            BrowserMediaSession.refresh()
            BrowserMediaSession.install { address -> if(address.toHttpUrl().host=="media.test") "session=old" else null }
            val handler=CookieHandler.getDefault()
            handler.put(URI("https://media.test/bootstrap"),mapOf("sEt-CoOkIe" to listOf(
                "session=new; Path=/stream/; Secure; HttpOnly",
                "foreign=secret; Domain=other.test; Path=/")))
            fun values(address:String)=BrowserMediaSession.loadForRequest(address.toHttpUrl()).map { it.name+"="+it.value }
            assertEquals(listOf("session=new"),values("https://media.test/stream/movie.m3u8"))
            assertEquals(listOf("session=old"),values("https://media.test/other/movie.m3u8"))
            assertEquals(listOf("session=old"),values("http://media.test/stream/movie.m3u8"))
            assertTrue(values("https://other.test/stream/movie.m3u8").isEmpty())
            handler.put(URI("https://media.test/stream/movie.m3u8"),mapOf("Set-Cookie" to listOf("session=; Max-Age=0; Path=/stream/; Secure")))
            assertTrue(values("https://media.test/stream/movie.m3u8").isEmpty())
            BrowserMediaSession.refresh()
            assertEquals(listOf("session=old"),values("https://media.test/stream/movie.m3u8"))
        } finally { BrowserMediaSession.refresh();BrowserMediaSession.install { null };CookieHandler.setDefault(previous) }
    }
}
