package com.braveplayer.music.settings

import android.content.Context
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppSettingsTest {

    private lateinit var context: Context
    private lateinit var settings: AppSettings

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        settings = AppSettings(context)
    }

    @Test
    fun testDefaultSettings() {
        assertThat(settings.isAutoplayEnabled()).isTrue()
        assertThat(settings.isSmartShuffleEnabled()).isFalse()
        assertThat(settings.isReducedIntensityEnabled()).isFalse()
        assertThat(settings.shouldAvoidRecentlyPlayed()).isTrue()
        assertThat(settings.isPersonalizationEnabled()).isTrue()
        assertThat(settings.getShuffleMode()).isEqualTo(AppSettings.SHUFFLE_MODE_NORMAL)
        assertThat(settings.getRecommendationDiversity()).isEqualTo(AppSettings.DIVERSITY_BALANCED)
    }

    @Test
    fun testReducedIntensityToggle() {
        assertThat(settings.isReducedIntensityEnabled()).isFalse()
        settings.setReducedIntensityEnabled(true)
        assertThat(settings.isReducedIntensityEnabled()).isTrue()
        settings.setReducedIntensityEnabled(false)
        assertThat(settings.isReducedIntensityEnabled()).isFalse()
    }

    @Test
    fun testAutoplayToggle() {
        settings.setAutoplayEnabled(false)
        assertThat(settings.isAutoplayEnabled()).isFalse()
        settings.setAutoplayEnabled(true)
        assertThat(settings.isAutoplayEnabled()).isTrue()
    }

    @Test
    fun testShuffleMode() {
        settings.setShuffleMode(AppSettings.SHUFFLE_MODE_SMART)
        assertThat(settings.getShuffleMode()).isEqualTo(AppSettings.SHUFFLE_MODE_SMART)
        settings.setShuffleMode(AppSettings.SHUFFLE_MODE_SHUFFLE)
        assertThat(settings.getShuffleMode()).isEqualTo(AppSettings.SHUFFLE_MODE_SHUFFLE)
    }

    @Test
    fun testRecommendationDiversity() {
        settings.setRecommendationDiversity(AppSettings.DIVERSITY_DISCOVERY)
        assertThat(settings.getRecommendationDiversity()).isEqualTo(AppSettings.DIVERSITY_DISCOVERY)
        settings.setRecommendationDiversity(AppSettings.DIVERSITY_FAMILIAR)
        assertThat(settings.getRecommendationDiversity()).isEqualTo(AppSettings.DIVERSITY_FAMILIAR)
    }

    @Test
    fun testMusicPreferencesDefaultsAndCustomization() {
        // Defaults
        val defaultGenres = settings.getFavouriteGenres()
        assertThat(defaultGenres).contains("Bollywood", "Pop", "Punjabi", "Romantic", "Lo-Fi")

        val defaultSingers = settings.getFavouriteSingers()
        assertThat(defaultSingers).contains("Arijit Singh", "Atif Aslam", "Darshan Raval")

        val defaultLanguages = settings.getPreferredLanguages()
        assertThat(defaultLanguages).contains("Hindi", "English", "Punjabi")

        // Customization
        val customGenres = setOf("Rock", "EDM", "Indie")
        settings.setFavouriteGenres(customGenres)
        assertThat(settings.getFavouriteGenres()).containsExactlyInAnyOrder("Rock", "EDM", "Indie")

        val customSingers = setOf("Darshan Raval", "The Weeknd")
        settings.setFavouriteSingers(customSingers)
        assertThat(settings.getFavouriteSingers()).containsExactlyInAnyOrder("Darshan Raval", "The Weeknd")

        val customLanguages = setOf("Punjabi", "English")
        settings.setPreferredLanguages(customLanguages)
        assertThat(settings.getPreferredLanguages()).containsExactlyInAnyOrder("Punjabi", "English")

        // Reset
        settings.resetMusicPreferences()
        assertThat(settings.getFavouriteGenres()).isEqualTo(AppSettings.DEFAULT_FAVOURITE_GENRES)
        assertThat(settings.getFavouriteSingers()).isEqualTo(AppSettings.DEFAULT_FAVOURITE_SINGERS)
        assertThat(settings.getPreferredLanguages()).isEqualTo(AppSettings.DEFAULT_PREFERRED_LANGUAGES)
    }
}
