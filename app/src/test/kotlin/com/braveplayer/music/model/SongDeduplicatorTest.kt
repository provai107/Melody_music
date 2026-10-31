package com.braveplayer.music.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class SongDeduplicatorTest {

    @Test
    fun testNormalizeTitle() {
        assertThat(SongDeduplicator.normalizeTitle("Satranga (From \"ANIMAL\")")).isEqualTo("satranga")
        assertThat(SongDeduplicator.normalizeTitle("Tum Hi Ho [From \"Aashiqui 2\"]")).isEqualTo("tum hi ho")
        assertThat(SongDeduplicator.normalizeTitle("Starboy (Official Audio)")).isEqualTo("starboy")
        assertThat(SongDeduplicator.normalizeTitle("Pasoori Nu (From \"Satyaprem Ki Katha\")")).isEqualTo("pasoori nu")
        assertThat(SongDeduplicator.normalizeTitle("Arjan Vailly (Original Motion Picture Soundtrack)")).isEqualTo("arjan vailly")
        assertThat(SongDeduplicator.normalizeTitle("Kahani Suno 2.0")).isEqualTo("kahani suno 2 0")
    }

    @Test
    fun testNormalizeArtists() {
        val norm1 = SongDeduplicator.normalizeArtists("Arijit Singh, Shreyas Puranik")
        val norm2 = SongDeduplicator.normalizeArtists("Shreyas Puranik, Arijit Singh")
        assertThat(norm1).isEqualTo(norm2)

        val norm3 = SongDeduplicator.normalizeArtists("The Weeknd ft. Daft Punk")
        val norm4 = SongDeduplicator.normalizeArtists("Daft Punk & The Weeknd")
        assertThat(norm3).isEqualTo(norm4)
    }

    @Test
    fun testAreDuplicatesSameNameSameSingersDifferentAlbum() {
        val songA = Song(
            id = "starboy_1",
            title = "Starboy",
            artists = "The Weeknd, Daft Punk",
            album = "Starboy",
            durationSec = 230,
            mediaUrl = "https://example.com/1.mp4",
            playCount = 39953481L
        )
        val songB = Song(
            id = "starboy_2",
            title = "Starboy",
            artists = "The Weeknd, Daft Punk",
            album = "The Highlights",
            durationSec = 230,
            mediaUrl = "https://example.com/2.mp4",
            playCount = 13920542L
        )
        assertThat(SongDeduplicator.areDuplicates(songA, songB)).isTrue()

        // Same song with movie tag vs plain title and reversed/overlapping artist list
        val satrangaOst = Song(
            id = "sat_1",
            title = "Satranga (From \"ANIMAL\")",
            artists = "Arijit Singh, Shreyas Puranik",
            album = "ANIMAL",
            durationSec = 270,
            mediaUrl = "https://example.com/sat1.mp4"
        )
        val satrangaCompilation = Song(
            id = "sat_2",
            title = "Satranga",
            artists = "Shreyas Puranik, Arijit Singh, Siddharth-Garima",
            album = "Best of 2023",
            durationSec = 270,
            mediaUrl = "https://example.com/sat2.mp4"
        )
        assertThat(SongDeduplicator.areDuplicates(satrangaOst, satrangaCompilation)).isTrue()

        // Distinct songs (Male vs Female version) are NOT duplicates
        val sunnRahaMale = Song(
            id = "sunn_m",
            title = "Sunn Raha Hai (Male)",
            artists = "Ankit Tiwari",
            album = "Aashiqui 2",
            durationSec = 390,
            mediaUrl = "https://example.com/male.mp4"
        )
        val sunnRahaFemale = Song(
            id = "sunn_f",
            title = "Sunn Raha Hai (Female)",
            artists = "Shreya Ghoshal",
            album = "Aashiqui 2",
            durationSec = 390,
            mediaUrl = "https://example.com/female.mp4"
        )
        assertThat(SongDeduplicator.areDuplicates(sunnRahaMale, sunnRahaFemale)).isFalse()

        // Completely different songs are NOT duplicates
        val huaMain = Song(
            id = "hua_1",
            title = "Hua Main",
            artists = "Pritam, Raghav Chaitanya",
            album = "ANIMAL",
            durationSec = 277,
            mediaUrl = "https://example.com/hua.mp4"
        )
        assertThat(SongDeduplicator.areDuplicates(satrangaOst, huaMain)).isFalse()
    }

    @Test
    fun testIsVoiceoverOrSpamDerivative() {
        val lofiSong = Song(
            id = "lofi_1",
            title = "Kahani Suno (Lofi)",
            artists = "Shubhadip Dey",
            album = "Kahani Suno (Lofi)",
            durationSec = 210,
            mediaUrl = "https://example.com/lofi.mp4"
        )
        // User searched "kahani suno" -> lofi is filtered out
        assertThat(SongDeduplicator.isVoiceoverOrSpamDerivative(lofiSong, "kahani suno")).isTrue()
        // User explicitly searched "kahani suno lofi" -> kept
        assertThat(SongDeduplicator.isVoiceoverOrSpamDerivative(lofiSong, "kahani suno lofi")).isFalse()

        val karaokeSong = Song(
            id = "karaoke_1",
            title = "Pasoori (Originally Performed by Shae Gill & Ali Sethi)",
            artists = "Backing Business",
            album = "Pristine Karaoke",
            durationSec = 220,
            mediaUrl = "https://example.com/karaoke.mp4"
        )
        assertThat(SongDeduplicator.isVoiceoverOrSpamDerivative(karaokeSong, "pasoori")).isTrue()

        val poemSong = Song(
            id = "poem_1",
            title = "Aurat (A Poem by Ali Zafar)",
            artists = "Ali Zafar",
            album = "Aurat",
            durationSec = 180,
            mediaUrl = "https://example.com/poem.mp4"
        )
        assertThat(SongDeduplicator.isVoiceoverOrSpamDerivative(poemSong, "ali zafar")).isTrue()

        val officialSong = Song(
            id = "starboy_official",
            title = "Starboy",
            artists = "The Weeknd, Daft Punk",
            album = "Starboy",
            durationSec = 230,
            mediaUrl = "https://example.com/official.mp4",
            playCount = 39953481L
        )
        assertThat(SongDeduplicator.isVoiceoverOrSpamDerivative(officialSong, "starboy")).isFalse()
    }

    @Test
    fun testIsMovieAlbumMatch() {
        assertThat(SongDeduplicator.isMovieAlbumMatch("animal", "ANIMAL")).isTrue()
        assertThat(SongDeduplicator.isMovieAlbumMatch("animal movie", "ANIMAL")).isTrue()
        assertThat(SongDeduplicator.isMovieAlbumMatch("kabir singh", "Kabir Singh")).isTrue()
        assertThat(SongDeduplicator.isMovieAlbumMatch("aashiqui 2", "Aashiqui 2")).isTrue()
        assertThat(SongDeduplicator.isMovieAlbumMatch("stree 2", "Stree 2")).isTrue()
        assertThat(SongDeduplicator.isMovieAlbumMatch("fighter", "Fighter (Original Motion Picture Soundtrack)")).isTrue()
        assertThat(SongDeduplicator.isMovieAlbumMatch("pushpa", "Pushpa - The Rise")).isTrue()

        // Artist and track queries should NOT match unrelated movie albums
        assertThat(SongDeduplicator.isMovieAlbumMatch("arijit singh", "Bhediya")).isFalse()
        assertThat(SongDeduplicator.isMovieAlbumMatch("tum hi ho", "Tum Mile")).isFalse()
    }

    @Test
    fun testDeduplicateAndRankSongsEliminatesDuplicateBanners() {
        val query = "starboy"
        val duplicates = listOf(
            Song(id = "1", title = "Starboy", artists = "The Weeknd, Daft Punk", album = "Starboy", durationSec = 230, mediaUrl = "https://saavn.com/1.mp4", playCount = 39953481L),
            Song(id = "2", title = "Starboy", artists = "The Weeknd, Daft Punk", album = "The Highlights", durationSec = 230, mediaUrl = "https://saavn.com/2.mp4", playCount = 13920542L),
            Song(id = "3", title = "Starboy", artists = "The Weeknd", album = "Top Hits", durationSec = 230, mediaUrl = "https://saavn.com/3.mp4", playCount = 1057474L),
            Song(id = "4", title = "Starboy (Kygo Remix)", artists = "The Weeknd, Kygo", album = "Starboy (Remix)", durationSec = 240, mediaUrl = "https://saavn.com/4.mp4", playCount = 525767L)
        )

        val result = SongDeduplicator.deduplicateAndRankSongs(duplicates, query)

        // All 3 duplicate banners of "Starboy" by The Weeknd are collapsed into exactly 1
        val mainStarboy = result.filter { it.title.equals("Starboy", ignoreCase = true) }
        assertThat(mainStarboy).hasSize(1)
        assertThat(mainStarboy[0].playCount).isEqualTo(39953481L)

        // Kygo Remix remains as separate distinct remix
        assertThat(result).hasSize(2)
        assertThat(result[0].title).isEqualTo("Starboy")
        assertThat(result[1].title).isEqualTo("Starboy (Kygo Remix)")
    }
}
