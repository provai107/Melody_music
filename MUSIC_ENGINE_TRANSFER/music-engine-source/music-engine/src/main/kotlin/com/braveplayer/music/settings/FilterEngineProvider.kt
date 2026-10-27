package com.braveplayer.music.settings

import com.braveplayer.filter.manager.FilterListManager
import com.braveplayer.filter.policy.AudioCdnPolicy

/**
 * Shared provider for the Brave-style filtering engine across UI, API services,
 * and background playback services. Ensures single source of truth for telemetry.
 */
object FilterEngineProvider {

    val audioCdnPolicy = AudioCdnPolicy(
        customProtectedCdnDomains = setOf(
            "soundhelix.com",
            "www.soundhelix.com",
            "saavncdn.com",
            "aac.saavncdn.com",
            "c.saavncdn.com",
            "jio.com",
            "jiotunepreview.jio.com"
        )
    )

    val filterListManager: FilterListManager = FilterListManager(
        initialRules = listOf(
            "||telemetry.adtracker.com^",
            "||evil-analytics.org^",
            "||adserver.net^",
            "@@||allowed-partner.com^"
        ),
        trustedDomains = setOf("soundhelix.com", "jio.com", "saavncdn.com"),
        audioCdnPolicy = audioCdnPolicy
    )
}
