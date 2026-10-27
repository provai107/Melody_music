package com.braveplayer.filter.model

/**
 * Result of evaluating a [FilterRequest] against active network filter policies and rules.
 */
data class FilterResult(
    val isBlocked: Boolean,
    val isAllowlisted: Boolean = false,
    val matchedRule: String? = null,
    val reason: String = ""
) {
    companion object {
        fun allowed(reason: String = "No matching block rule"): FilterResult {
            return FilterResult(isBlocked = false, isAllowlisted = false, reason = reason)
        }

        fun allowlisted(rule: String, reason: String = "Matched exception rule"): FilterResult {
            return FilterResult(isBlocked = false, isAllowlisted = true, matchedRule = rule, reason = reason)
        }

        fun blocked(rule: String, reason: String = "Matched block rule"): FilterResult {
            return FilterResult(isBlocked = true, isAllowlisted = false, matchedRule = rule, reason = reason)
        }

        fun audioBypass(reason: String = "Audio CDN protection bypass"): FilterResult {
            return FilterResult(isBlocked = false, isAllowlisted = true, matchedRule = "AUDIO_CDN_BYPASS", reason = reason)
        }
    }
}
