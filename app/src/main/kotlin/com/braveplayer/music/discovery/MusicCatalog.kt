package com.braveplayer.music.discovery

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
}
