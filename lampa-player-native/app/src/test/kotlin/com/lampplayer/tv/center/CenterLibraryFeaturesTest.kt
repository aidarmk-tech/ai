package com.lampplayer.tv.center

import android.app.Application
import com.google.gson.*
import com.lampplayer.tv.data.datastore.PositionDataStore
import com.lampplayer.tv.domain.model.CardMeta
import com.lampplayer.tv.domain.model.PlaybackPosition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[28])
class CenterLibraryFeaturesTest {
    @Test fun statusesPersistAndNextSeriesNeverInventsOrSkipsAnEpisode() {
        val app=RuntimeEnvironment.getApplication();val store=CenterStore(app)
        val item=CenterTitle(70001,"tv","Сериал")
        store.toggleFollow(item);store.setSeriesStatus(item.key,"waiting")
        assertEquals("Жду продолжения",CenterStore(app).mySeries().single().updateLabel)
        store.remember("https://video.test/one",CardMeta(item.title,tmdbId=item.id,contentKey=item.key,seasonNumber=1,episodeNumber=1))
        store.rememberEpisodes(item,listOf(OnlineEpisode(1,3,"Третья","Voice",mapOf(720 to "https://video.test/three.m3u8"))))
        assertNull(store.nextKnownEpisode(item))
        store.rememberEpisodes(item,listOf(OnlineEpisode(1,2,"Вторая","Voice",mapOf(720 to "https://video.test/two.m3u8"))))
        assertEquals(1 to 2,store.nextKnownEpisode(item))
        assertTrue(runCatching { store.setSeriesStatus(item.key,"invented") }.isFailure)
    }
    @Test fun homeShelfOrderAndVisibilityBelongToEachCatalog() {
        val store=CenterStore(RuntimeEnvironment.getApplication())
        val original=store.homeOrder("tmdb");val changed=original.reversed()
        store.setHomeOrder("tmdb",changed);store.toggleHomeShelf("tmdb","pinned_movies")
        assertEquals(changed,store.homeOrder("tmdb"));assertTrue("pinned_movies" in store.homeHidden("tmdb"))
        assertTrue(store.homeHidden("num").isEmpty());assertNotEquals(changed,store.homeOrder("num"))
        assertTrue(runCatching { store.setHomeOrder("tmdb",listOf("new_movies")) }.isFailure)
        store.resetHome("tmdb");assertEquals(original,store.homeOrder("tmdb"))
    }
    @Test fun backupExcludesConnectionSecretsAndCookiesAndMergesWithoutDuplicates() = runBlocking {
        val app=RuntimeEnvironment.getApplication();val store=CenterStore(app);val positions=PositionDataStore(app)
        store.indexer="https://private-indexer.test";store.apiKey="secret-key"
        val first=CenterTitle(70002,"movie","Первый");val second=CenterTitle(70003,"movie","Второй")
        store.toggle(first)
        val card=CardMeta(first.title,tmdbId=first.id,contentKey=first.key,headers=mapOf("Cookie" to "secret-cookie"))
        store.remember("https://video.test/first",card)
        positions.savePosition("https://video.test/first",card,PlaybackPosition(time=300.0,updated=100))
        val backup=CenterBackup(app).export()
        assertFalse(backup.contains("secret-key"));assertFalse(backup.contains("private-indexer"));assertFalse(backup.contains("secret-cookie"))
        store.toggle(second);positions.savePosition("https://video.test/first",card,PlaybackPosition(time=900.0,updated=200))
        CenterBackup(app).restore(backup);CenterBackup(app).restore(backup)
        assertEquals(setOf(first.key,second.key),store.favorites().map { it.key }.toSet())
        assertEquals(2,store.favorites().size)
        assertEquals(900.0,positions.getPosition("https://video.test/first",card)!!.time,0.0)
        assertEquals("secret-key",store.apiKey)
    }
    @Test fun corruptBackupIsRejectedBeforeAnyLibraryOrProgressMutation() = runBlocking {
        val app=RuntimeEnvironment.getApplication();val store=CenterStore(app)
        val existing=CenterTitle(70004,"movie","Существующий");store.toggle(existing)
        val root=JsonParser.parseString(CenterBackup(app).export()).asJsonObject
        val injected=JsonObject().apply { addProperty("r123","""{"time":-100,"duration":100,"percent":5,"updated":100}""") }
        root.add("positions",injected)
        root.getAsJsonObject("library").addProperty("favorites","""[{"id":70005,"type":"movie","title":"Новый"}]""")
        assertTrue(runCatching { CenterBackup(app).restore(root.toString()) }.isFailure)
        assertEquals(listOf(existing.key),store.favorites().map { it.key })
        assertFalse(PositionDataStore(app).backupEntries().containsKey("r123"))
    }
    @Test fun freshInstallationCanRestoreStableEpisodeProgressAndStatus() = runBlocking {
        val app=RuntimeEnvironment.getApplication();val store=CenterStore(app);val card=CardMeta("Сериал",tmdbId=70006,contentKey="tmdb:tv:70006",seasonNumber=2,episodeNumber=5)
        val key=PositionDataStore(app).episodeKey(card)!!
        val root=JsonObject().apply {
            addProperty("format","LampMedia");addProperty("version",1)
            add("library",JsonObject().apply { addProperty("series_status_tmdb:tv:70006","waiting") })
            add("positions",JsonObject().apply { addProperty(key,"""{"time":725,"duration":1200,"percent":60,"updated":500}""") })
        }
        CenterBackup(app).restore(root.toString())
        assertEquals("waiting",store.seriesStatus("tmdb:tv:70006"))
        assertEquals(725.0,PositionDataStore(app).getPosition("https://video.test/new-token",card)!!.time,0.0)
    }
}
