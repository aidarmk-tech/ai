package com.lampplayer.tv.center

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CenterEngineInstrumentedTest {
    @Test fun publicProviderCountryCatalogsLoadOnAndroid() = runBlocking {
        val catalog=CenterCatalog()
        for ((provider,base,section) in listOf(
            Triple("kinovod","https://kinovod.pro","kz_movies"),
            Triple("filmix","https://filmix.gg","kz_series"))) {
            val items=catalog.browse(provider,base,CenterSection.all.first { it.id==section })
            assertTrue("$provider catalog is empty",items.isNotEmpty())
            assertTrue(items.all { it.provider==provider && it.sourceUrl?.startsWith("https://")==true })
        }
        assertTrue(catalog.search("filmix","https://filmix.gg","Интерстеллар").any { it.title.contains("Интерстеллар",true) })
    }
    @Test fun installedNativeExecutableStartsAndAnswersOnLoopback() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MediaCenterActivity::class.java).use {
            try {
                val base=CenterEngineService.ensure(context)
                assertTrue(base.startsWith("http://127.0.0.1:"))
                OkHttpClient().newCall(Request.Builder().url("$base/echo").build()).execute().use { response ->
                    assertTrue(response.isSuccessful)
                    assertTrue(response.body!!.string().contains("148"))
                }
                val backend=CenterBackend()
                val original=(backend.engineSettings(base).get("CacheSize").asLong/(1024*1024)).toInt()
                backend.setEngineCache(base,32)
                assertEquals(32L*1024*1024,backend.engineSettings(base).get("CacheSize").asLong)
                backend.setEngineCache(base,if(original in setOf(32,64,128,256)) original else 64)
            } finally { CenterEngineService.stop(context) }
        }
    }

    @Test fun replacementCatalogsLoadBothMediaTypesAndRussianSearchOnAndroid() = runBlocking {
        coroutineScope {
            listOf("kinotac","zona","kinokong").map { provider -> async {
                val catalog=CenterCatalog();val base=CenterCatalog.defaultHost(provider)
                for(section in CenterCatalog.sections(provider)) {
                    val items=catalog.browse(provider,base,section)
                    assertTrue("$provider ${section.type}: empty catalog",items.isNotEmpty())
                    assertTrue("$provider: wrong media identity",items.all { it.provider==provider&&it.type==section.type&&it.sourceUrl?.startsWith(base)==true })
                    assertTrue("$provider: no covers",items.any { !it.poster.isNullOrBlank() })
                    val next=catalog.browse(provider,base,section,2)
                    assertTrue("$provider: no next catalog page",next.isNotEmpty())
                    assertTrue("$provider: repeated page",next.any { row -> items.none { it.key==row.key } })
                }
                val results=catalog.search(provider,base,"Матрица")
                assertTrue("$provider: Russian search failed",results.any { it.title.contains("Матриц",true) })
            } }.awaitAll()
            Unit
        }
    }

    @Test fun newCatalogTitleDetailsContainTheSelectedSynopsisCastAndReleaseYear() = runBlocking {
        coroutineScope {
            listOf(
                CenterTitle(20817,"tv","Король Талсы",provider="kinotac",sourceUrl="https://kinotac.org/20817-korol-talsy.html") to "2022",
                CenterTitle(1478,"tv","Гангстерленд",provider="zona",sourceUrl="https://zona.movie/series/mobland-1478/") to "2025",
                CenterTitle(61709,"tv","Док - Всё в твоих руках",provider="kinokong",sourceUrl="https://kinokong.es/61709-serial-dok-vse-v-tvoih-rukah-1-4-sezon.html") to "2020"
            ).map { (item,year) -> async {
                val detail=CenterCatalog().detail(item)
                assertEquals(item.key,detail.title.key);assertEquals(year,detail.title.year)
                assertTrue("Selected synopsis is missing",detail.title.overview.isNotBlank())
                assertTrue("Selected cast is missing",detail.actors.isNotBlank())
                assertTrue("Selected cover is missing",detail.title.poster?.startsWith("https://")==true)
                if(item.provider=="zona") {
                    assertEquals("mobland",detail.title.original.lowercase())
                    assertNotNull(detail.title.backdrop);assertNotEquals(detail.title.poster,detail.title.backdrop)
                }
            } }.awaitAll()
            Unit
        }
    }

    @Test fun tmdbTitleFindsMatchingKinovodSeriesWithoutReusingItsNativeId() = runBlocking {
        val item=CenterTitle(153312,"tv","Король Талсы",original="Tulsa King",year="2022")
        val results=CenterCatalog().search("kinovod",CenterCatalog.defaultHost("kinovod"),item.title)
        val matches=CenterOnlineMatch.candidates(item,results)
        assertTrue("KinoVod search did not return the selected series",matches.any { CenterOnlineMatch.exact(item,it) })
        assertTrue(matches.all { it.provider=="kinovod"&&it.type=="tv"&&it.id!=item.id })
    }
}
