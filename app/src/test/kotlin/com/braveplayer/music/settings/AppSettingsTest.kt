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
}
