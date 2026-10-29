package com.braveplayer.music.discovery

import com.braveplayer.music.model.GenreCardTheme

data class GenreCategory(
    val id: String,
    val name: String,
    val subtitle: String,
    val emoji: String,
    val query: String
)

data class MoodCategory(
    val id: String,
    val name: String,
    val subtitle: String,
    val emoji: String,
    val query: String
)

object MusicCatalog {

    val GENRES = listOf(
        GenreCategory("pop", "Pop", "Global Hits", "🎤", "Pop Hits"),
        GenreCategory("hiphop", "Hip-Hop", "Beats & Bars", "🎧", "Hip Hop Hits"),
        GenreCategory("rock", "Rock", "Anthems & Guitars", "🎸", "Rock Classics"),
        GenreCategory("edm", "EDM / Dance", "Club & Festival", "⚡", "Dance Party EDM"),
        GenreCategory("bollywood", "Bollywood", "Top Soundtracks", "🎬", "Bollywood Hits"),
        GenreCategory("punjabi", "Punjabi", "High-Energy Urban", "🥁", "Punjabi Hits"),
        GenreCategory("lofi", "Lo-Fi Beats", "Chillhop & Study", "☕", "Lo-Fi Beats"),
        GenreCategory("rnb", "R&B / Soul", "Smooth Late-Night", "🎷", "R&B Soul"),
        GenreCategory("indie", "Indie", "Alternative & Folk", "🌿", "Indie Alternative"),
        GenreCategory("acoustic", "Acoustic", "Unplugged Sessions", "🪕", "Acoustic Hits"),
        GenreCategory("classical", "Classical", "Piano & Orchestra", "🎻", "Classical Piano"),
        GenreCategory("romantic", "Romantic", "Love Ballads", "💖", "Romantic Hits")
    )

    val GENRE_THEMES: List<GenreCardTheme> = listOf(
        GenreCardTheme(
            id = "pop",
            displayName = "Pop",
            subtitle = "Global Hits",
            emoji = "🎤",
            query = "Pop Hits",
            backgroundColorHex = "#D81B60",
            artworkUrl = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=300&q=80"
        ),
        GenreCardTheme(
            id = "hiphop",
            displayName = "Hip-Hop",
            subtitle = "Beats & Bars",
            emoji = "🎧",
            query = "Hip Hop Hits",
            backgroundColorHex = "#FB8C00",
            artworkUrl = "https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=300&q=80"
        ),
        GenreCardTheme(
            id = "rock",
            displayName = "Rock",
            subtitle = "Anthems & Guitars",
            emoji = "🎸",
            query = "Rock Classics",
            backgroundColorHex = "#E53935",
            artworkUrl = "https://images.unsplash.com/photo-1498038432885-c6f3f1b912ee?w=300&q=80"
        ),
        GenreCardTheme(
            id = "edm",
            displayName = "EDM / Dance",
            subtitle = "Club & Festival",
            emoji = "⚡",
            query = "Dance Party EDM",
            backgroundColorHex = "#00ACC1",
            artworkUrl = "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=300&q=80"
        ),
        GenreCardTheme(
            id = "bollywood",
            displayName = "Bollywood",
            subtitle = "Top Soundtracks",
            emoji = "🎬",
            query = "Bollywood Hits",
            backgroundColorHex = "#8E24AA",
            artworkUrl = "https://images.unsplash.com/photo-1518609878373-06d740f60d8b?w=300&q=80"
        ),
        GenreCardTheme(
            id = "punjabi",
            displayName = "Punjabi",
            subtitle = "High-Energy Urban",
            emoji = "🥁",
            query = "Punjabi Hits",
            backgroundColorHex = "#F4511E",
            artworkUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=300&q=80"
        ),
        GenreCardTheme(
            id = "lofi",
            displayName = "Lo-Fi Beats",
            subtitle = "Chillhop & Study",
            emoji = "☕",
            query = "Lo-Fi Beats",
            backgroundColorHex = "#5E35B1",
            artworkUrl = "https://images.unsplash.com/photo-1501386761578-eac5c94b800a?w=300&q=80"
        ),
        GenreCardTheme(
            id = "rnb",
            displayName = "R&B / Soul",
            subtitle = "Smooth Late-Night",
            emoji = "🎷",
            query = "R&B Soul",
            backgroundColorHex = "#3949AB",
            artworkUrl = "https://images.unsplash.com/photo-1511192336575-5a79af67a629?w=300&q=80"
        ),
        GenreCardTheme(
            id = "indie",
            displayName = "Indie",
            subtitle = "Alternative & Folk",
            emoji = "🌿",
            query = "Indie Alternative",
            backgroundColorHex = "#43A047",
            artworkUrl = "https://images.unsplash.com/photo-1465847899084-d164df4dedc6?w=300&q=80"
        ),
        GenreCardTheme(
            id = "acoustic",
            displayName = "Acoustic",
            subtitle = "Unplugged Sessions",
            emoji = "🪕",
            query = "Acoustic Hits",
            backgroundColorHex = "#8D6E63",
            artworkUrl = "https://images.unsplash.com/photo-1510915361894-db8b60106cb1?w=300&q=80"
        ),
        GenreCardTheme(
            id = "classical",
            displayName = "Classical",
            subtitle = "Piano & Orchestra",
            emoji = "🎻",
            query = "Classical Piano",
            backgroundColorHex = "#546E7A",
            artworkUrl = "https://images.unsplash.com/photo-1520523839898-5071270503bb?w=300&q=80"
        ),
        GenreCardTheme(
            id = "romantic",
            displayName = "Romantic",
            subtitle = "Love Ballads",
            emoji = "💖",
            query = "Romantic Hits",
            backgroundColorHex = "#EC407A",
            artworkUrl = "https://images.unsplash.com/photo-1518199266791-5375a83190b7?w=300&q=80"
        ),
        GenreCardTheme(
            id = "chill",
            displayName = "Chill & Relax",
            subtitle = "Peaceful Ambient",
            emoji = "🧘",
            query = "Chill Relaxing Music",
            backgroundColorHex = "#00897B",
            artworkUrl = "https://images.unsplash.com/photo-1506126613408-eca07ce68773?w=300&q=80"
        ),
        GenreCardTheme(
            id = "workout",
            displayName = "Workout & Energy",
            subtitle = "Power & Motivation",
            emoji = "🔥",
            query = "Workout Motivation Energy",
            backgroundColorHex = "#C62828",
            artworkUrl = "https://images.unsplash.com/photo-1534438327276-14e5300c3a48?w=300&q=80"
        ),
        GenreCardTheme(
            id = "focus",
            displayName = "Deep Focus",
            subtitle = "Study & Flow State",
            emoji = "🎯",
            query = "Focus Instrumental Study",
            backgroundColorHex = "#3F51B5",
            artworkUrl = "https://images.unsplash.com/photo-1434030216411-0b793f4b4173?w=300&q=80"
        ),
        GenreCardTheme(
            id = "party",
            displayName = "Party & Dance",
            subtitle = "Turn Up The Volume",
            emoji = "🎉",
            query = "Party Dance Festival",
            backgroundColorHex = "#7B1FA2",
            artworkUrl = "https://images.unsplash.com/photo-1492684223066-81342ee5ff30?w=300&q=80"
        )
    )

    fun findThemeForId(id: String): GenreCardTheme {
        return GENRE_THEMES.firstOrNull { it.id.equals(id, ignoreCase = true) }
            ?: GENRE_THEMES.first()
    }

    val MOODS = listOf(
        MoodCategory("chill", "Chill & Relax", "Unwind & breathe", "🧘", "Chill Relax"),
        MoodCategory("workout", "Workout & Energy", "Power & motivation", "⚡", "Workout Energy Motivation"),
        MoodCategory("focus", "Deep Focus", "Study & flow state", "🎯", "Focus Study Instrumental"),
        MoodCategory("party", "Party & Dance", "Turn it up loud", "🎉", "Party Dance Hits"),
        MoodCategory("romantic", "Romantic Vibes", "Candlelight & love", "💖", "Romantic Love Songs"),
        MoodCategory("sleep", "Sleep & Night", "Peaceful ambient", "🌙", "Sleep Ambient Meditation"),
        MoodCategory("happy", "Feel Good", "Upbeat & sunshine", "☀️", "Happy Upbeat Songs"),
        MoodCategory("sad", "Melancholy", "Emotional solace", "🌧️", "Sad Melancholic Songs")
    )

    val CHANNELS = listOf(
        GenreCategory("romance", "Romance", "Love & Passion", "💖", "Romance Love Hits"),
        GenreCategory("dance", "Dance", "Club & Festival", "⚡", "Dance Party EDM"),
        GenreCategory("workout", "Workout", "Power & Motivation", "🔥", "Workout Motivation Energy"),
        GenreCategory("1990s", "The 1990s", "Golden Era Classics", "📻", "90s Nostalgia Hits"),
        GenreCategory("chill", "Chill", "Peaceful Solace", "🧘", "Chill Relaxing Acoustic"),
        GenreCategory("focus", "Focus", "Flow State & Study", "🎯", "Deep Focus Instrumental"),
        GenreCategory("bollywood", "Bollywood", "Chartbusters & Soundtracks", "🎬", "Bollywood Top Hits"),
        GenreCategory("punjabi", "Punjabi", "High-Energy Urban", "🥁", "Punjabi Urban Hits"),
        GenreCategory("rock", "Rock", "Anthems & Guitars", "🎸", "Rock Classics"),
        GenreCategory("hiphop", "Hip-Hop", "Beats & Rhymes", "🎧", "Hip Hop Rap Hits"),
        GenreCategory("acoustic", "Acoustic", "Warm & Intimate", "☕", "Acoustic Unplugged"),
        GenreCategory("ambient", "Ambient & Sleep", "Deep Relaxation", "🌙", "Sleep Ambient Meditation")
    )
}
