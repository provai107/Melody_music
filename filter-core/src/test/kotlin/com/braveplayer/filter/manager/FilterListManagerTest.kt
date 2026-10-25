package com.braveplayer.filter.manager

import com.braveplayer.filter.model.FilterRequest
import com.braveplayer.filter.model.RequestType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FilterListManagerTest {

    private val initialRules = listOf(
        "||adserver.net^",
        "@@||adserver.net/exception^"
    )

    @Test
    @DisplayName("FilterListManager updates rules atomically with valid checksum")
    fun testSafeAtomicUpdateWithChecksum() {
        val manager = FilterListManager(initialRules)

        val newRulesText = "||newtracker.org^\n||telemetry.bad^"
        // Calculate expected sha256
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val expectedSha = digest.digest(newRulesText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val result = manager.updateRules(newRulesText, expectedSha)
        assertThat(result).isInstanceOf(FilterListManager.UpdateResult.Success::class.java)

        // Verify that new rules are in effect
        val reqNew = FilterRequest("https://newtracker.org/ping", RequestType.TRACKER_ANALYTICS)
        assertThat(manager.evaluate(reqNew).isBlocked).isTrue()
    }

    @Test
    @DisplayName("FilterListManager rejects update when checksum fails and retains active rules")
    fun testChecksumMismatchRejection() {
        val manager = FilterListManager(initialRules)

        val newRulesText = "||newtracker.org^"
        val bogusSha = "0000000000000000000000000000000000000000000000000000000000000000"

        val result = manager.updateRules(newRulesText, bogusSha)
        assertThat(result).isInstanceOf(FilterListManager.UpdateResult.IntegrityError::class.java)

        // Existing rules must still be active and intact
        val reqOld = FilterRequest("https://adserver.net/track", RequestType.TRACKER_ANALYTICS)
        assertThat(manager.evaluate(reqOld).isBlocked).isTrue()

        // Bogus rule from rejected update must NOT be active
        val reqNew = FilterRequest("https://newtracker.org/ping", RequestType.TRACKER_ANALYTICS)
        assertThat(manager.evaluate(reqNew).isBlocked).isFalse()
    }

    @Test
    @DisplayName("FilterListManager supports rollback to previous engine")
    fun testRollback() {
        val manager = FilterListManager(initialRules)

        // Apply an update
        manager.updateRules("||secondversion.com^")
        val reqSecond = FilterRequest("https://secondversion.com/pixel", RequestType.TRACKER_ANALYTICS)
        assertThat(manager.evaluate(reqSecond).isBlocked).isTrue()

        // Rollback
        val rolledBack = manager.rollbackToPrevious()
        assertThat(rolledBack).isTrue()

        // Previous engine active again: initialRules are active, secondversion is not
        assertThat(manager.evaluate(reqSecond).isBlocked).isFalse()
        val reqOld = FilterRequest("https://adserver.net/track", RequestType.TRACKER_ANALYTICS)
        assertThat(manager.evaluate(reqOld).isBlocked).isTrue()
    }

    @Test
    @DisplayName("Blocking statistics accurately record metrics across requests")
    fun testStatisticsTracking() {
        val manager = FilterListManager(initialRules)

        manager.evaluate(FilterRequest("https://adserver.net/ad.png", RequestType.TRACKER_ANALYTICS)) // Blocked
        manager.evaluate(FilterRequest("https://adserver.net/ad.png", RequestType.TRACKER_ANALYTICS)) // Blocked
        manager.evaluate(FilterRequest("https://adserver.net/exception/ok", RequestType.TRACKER_ANALYTICS)) // Allowlisted
        manager.evaluate(FilterRequest("https://clean-api.com/songs", RequestType.METADATA_API)) // Allowed
        manager.evaluate(FilterRequest("https://audio.cdn.com/stream.mp3", RequestType.AUDIO_PLAYBACK)) // Audio bypass

        val stats = manager.getStatisticsSnapshot()
        assertThat(stats.totalRequestsEvaluated).isEqualTo(5)
        assertThat(stats.blockedRequests).isEqualTo(2)
        assertThat(stats.allowlistedRequests).isEqualTo(1)
        assertThat(stats.allowedRequests).isEqualTo(1)
        assertThat(stats.audioStreamBypasses).isEqualTo(1)
        assertThat(stats.lastBlockedTimestampMs).isNotNull()
        assertThat(stats.topBlockedDomains).containsEntry("adserver.net", 2L)
    }

    @Test
    @DisplayName("Concurrent reads remain wait-free and safe while updates are applied")
    fun testConcurrentReadsDuringUpdates() {
        val manager = FilterListManager(initialRules)
        val executor = Executors.newFixedThreadPool(4)

        val readerCount = 1000
        val errorCount = java.util.concurrent.atomic.AtomicInteger(0)

        // Submit concurrent reader tasks
        for (i in 0 until readerCount) {
            executor.submit {
                try {
                    val req = FilterRequest("https://adserver.net/test$i", RequestType.TRACKER_ANALYTICS)
                    manager.evaluate(req)
                } catch (e: Exception) {
                    errorCount.incrementAndGet()
                }
            }
        }

        // Apply simultaneous updates in another thread
        executor.submit {
            manager.updateRules("||adserver.net^\n||concurrent-rule.org^")
        }

        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertThat(errorCount.get()).isEqualTo(0)
    }
}
