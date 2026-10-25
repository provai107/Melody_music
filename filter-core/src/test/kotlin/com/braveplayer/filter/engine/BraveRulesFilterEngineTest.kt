package com.braveplayer.filter.engine

import com.braveplayer.filter.model.FilterRequest
import com.braveplayer.filter.model.RequestType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class BraveRulesFilterEngineTest {

    private val sampleRules = listOf(
        "||ads.example.com^",
        "||telemetry.musicapi.org^",
        "||tracker.io^",
        "@@||ads.example.com/partner_sponsor^",
        "@@||allowed-analytics.com^",
        "/pixel_tracker.gif*"
    )

    private val trustedDomains = setOf("musicapi.org", "googlevideo.com", "spotify.com")

    private val engine = BraveRulesFilterEngine(
        rules = sampleRules,
        trustedDomains = trustedDomains
    )

    @Test
    @DisplayName("Exact-host matching blocks targeted hostname but permits sibling hosts")
    fun testExactHostMatching() {
        val blockedReq = FilterRequest(
            url = "https://ads.example.com/banner.js",
            requestType = RequestType.METADATA_API
        )
        val result = engine.evaluate(blockedReq)
        assertThat(result.isBlocked).isTrue()
        assertThat(result.matchedRule).isEqualTo("||ads.example.com^")

        val allowedSibling = FilterRequest(
            url = "https://example.com/home",
            requestType = RequestType.METADATA_API
        )
        val allowedResult = engine.evaluate(allowedSibling)
        assertThat(allowedResult.isBlocked).isFalse()
    }

    @Test
    @DisplayName("Subdomain matching blocks apex domain and all subdomains")
    fun testSubdomainMatching() {
        // Apex domain
        val req1 = FilterRequest("https://tracker.io/collect", RequestType.TRACKER_ANALYTICS)
        assertThat(engine.evaluate(req1).isBlocked).isTrue()

        // 1-level subdomain
        val req2 = FilterRequest("https://eu.tracker.io/metrics", RequestType.TRACKER_ANALYTICS)
        assertThat(engine.evaluate(req2).isBlocked).isTrue()

        // Multi-level nested subdomain
        val req3 = FilterRequest("https://dev.metrics.eu.tracker.io/ping", RequestType.TRACKER_ANALYTICS)
        assertThat(engine.evaluate(req3).isBlocked).isTrue()

        // Similar but unrelated domain must NOT be blocked
        val reqUnrelated = FilterRequest("https://mytracker.io/info", RequestType.METADATA_API)
        assertThat(engine.evaluate(reqUnrelated).isBlocked).isFalse()

        val reqSuffix = FilterRequest("https://tracker.io.attacker.com/fake", RequestType.METADATA_API)
        assertThat(engine.evaluate(reqSuffix).isBlocked).isFalse()
    }

    @Test
    @DisplayName("Lookalike domain defense blocks homoglyph attacks and impostor CDN hosts")
    fun testLookalikeDomainDefense() {
        // Homoglyph attack: Cyrillic 'о' in spotify.com (sp\u043Etify.com)
        val homoglyphUrl = "https://sp\u043Etify.com/track/123"
        val homoglyphReq = FilterRequest(homoglyphUrl, RequestType.METADATA_API)
        val homoglyphResult = engine.evaluate(homoglyphReq)
        assertThat(homoglyphResult.isBlocked).isTrue()
        assertThat(homoglyphResult.matchedRule).isEqualTo("LOOKALIKE_HOMOGLYPH_ATTACK")

        // Impostor domain spoofing googlevideo.com
        val impostorUrl = "https://googlevideo.com.attacker.com/stream.mp3"
        val impostorReq = FilterRequest(impostorUrl, RequestType.AUDIO_PLAYBACK)
        val impostorResult = engine.evaluate(impostorReq)
        assertThat(impostorResult.isBlocked).isTrue()
        assertThat(impostorResult.matchedRule).isEqualTo("LOOKALIKE_IMPOSTOR_ATTACK")
    }

    @Test
    @DisplayName("Allowlisted requests override block rules")
    fun testAllowlistedRequests() {
        // This URL matches block rule '||ads.example.com^' but also matches exception '@@||ads.example.com/partner_sponsor^'
        val sponsoredReq = FilterRequest(
            url = "https://ads.example.com/partner_sponsor/info.json",
            requestType = RequestType.METADATA_API
        )
        val result = engine.evaluate(sponsoredReq)
        assertThat(result.isBlocked).isFalse()
        assertThat(result.isAllowlisted).isTrue()
        assertThat(result.matchedRule).contains("@@||ads.example.com/partner_sponsor^")

        // Standalone allowlisted domain
        val allowedAnalytics = FilterRequest("https://allowed-analytics.com/event", RequestType.METADATA_API)
        val allowedResult = engine.evaluate(allowedAnalytics)
        assertThat(allowedResult.isBlocked).isFalse()
        assertThat(allowedResult.isAllowlisted).isTrue()
    }

    @Test
    @DisplayName("Legitimate API requests not matching any blocklist are permitted")
    fun testLegitimateApiRequests() {
        val legitimateReq = FilterRequest(
            url = "https://musicapi.org/v1/search?q=Mozart",
            requestType = RequestType.METADATA_API
        )
        val result = engine.evaluate(legitimateReq)
        assertThat(result.isBlocked).isFalse()
        assertThat(result.isAllowlisted).isFalse()
    }
}
