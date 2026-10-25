package com.braveplayer.filter.engine

import com.braveplayer.filter.model.FilterRequest
import com.braveplayer.filter.model.FilterResult
import com.braveplayer.filter.model.RequestType
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * High-performance, pure Kotlin implementation of Brave / ABP network rule matching.
 *
 * Implements:
 * - Domain anchor matching: `||example.com^` matches `example.com` and all subdomains (`*.example.com`).
 * - Delimiter matching: `^` delimiter conforms to ABP/Brave spec (matches `/`, `?`, `:`, or end of string, not word characters).
 * - Exception / Allowlist matching: `@@||example.com^` takes immediate precedence over block rules.
 * - Path & substring wildcard patterns: `||tracker.com/pixel.gif*`.
 * - Lookalike domain and homoglyph defense.
 */
class BraveRulesFilterEngine(
    rules: List<String> = emptyList(),
    private val trustedDomains: Set<String> = emptySet()
) : NetworkFilterEngine {

    override val engineName: String = "BraveRulesFilterEngine(Kotlin)"

    // Allowlist rules (@@) indexed by domain anchor
    private val allowlistDomainRules = ConcurrentHashMap<String, MutableList<CompiledRule>>()
    private val allowlistGenericRules = mutableListOf<CompiledRule>()

    // Blocklist rules indexed by domain anchor for O(1) apex/subdomain traversal
    private val blocklistDomainRules = ConcurrentHashMap<String, MutableList<CompiledRule>>()
    private val blocklistGenericRules = mutableListOf<CompiledRule>()

    data class CompiledRule(
        val rawRule: String,
        val isAllowlist: Boolean,
        val domainAnchor: String?,
        val regex: Pattern?,
        val exactPath: String?,
        val isThirdPartyOnly: Boolean,
        val appliesToAudio: Boolean
    )

    init {
        loadRules(rules)
    }

    private fun loadRules(rules: List<String>) {
        for (raw in rules) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("#")) {
                continue // Skip comments and empty lines
            }
            parseAndAddRule(trimmed)
        }
    }

    private fun parseAndAddRule(rawRule: String) {
        val isAllowlist = rawRule.startsWith("@@")
        val ruleText = if (isAllowlist) rawRule.substring(2) else rawRule

        // Options: $third-party, $media, etc.
        val parts = ruleText.split('$', limit = 2)
        val patternPart = parts[0]
        val optionsPart = if (parts.size > 1) parts[1] else ""

        val isThirdParty = optionsPart.contains("third-party")
        val optionsList = if (optionsPart.isNotEmpty()) optionsPart.split(',') else emptyList()
        val explicitNonMediaTypes = setOf("script", "image", "stylesheet", "xmlhttprequest", "subdocument", "document", "websocket")
        val hasExplicitNonMedia = optionsList.any { it in explicitNonMediaTypes }
        val hasMediaOption = optionsList.contains("media")
        val excludesMedia = optionsList.contains("~media")
        val appliesToAudio = !excludesMedia && (hasMediaOption || !hasExplicitNonMedia)

        var domainAnchor: String? = null
        var regex: Pattern? = null
        var exactPath: String? = null

        if (patternPart.startsWith("||")) {
            val afterAnchor = patternPart.substring(2)
            val separatorIndex = afterAnchor.indexOfAny(charArrayOf('^', '/', '?'))
            if (separatorIndex == -1) {
                // Exact domain without path or separator, e.g. ||example.com
                domainAnchor = afterAnchor.lowercase()
            } else {
                domainAnchor = afterAnchor.substring(0, separatorIndex).lowercase()
                val remainder = afterAnchor.substring(separatorIndex)
                if (remainder == "^") {
                    // Standard ||domain.com^
                    regex = null
                } else {
                    regex = compilePatternToRegex(remainder)
                }
            }
        } else {
            // General URL or path pattern
            regex = compilePatternToRegex(patternPart)
        }

        val compiled = CompiledRule(
            rawRule = rawRule,
            isAllowlist = isAllowlist,
            domainAnchor = domainAnchor,
            regex = regex,
            exactPath = exactPath,
            isThirdPartyOnly = isThirdParty,
            appliesToAudio = appliesToAudio
        )

        if (isAllowlist) {
            if (domainAnchor != null) {
                allowlistDomainRules.computeIfAbsent(domainAnchor) { mutableListOf() }.add(compiled)
            } else {
                allowlistGenericRules.add(compiled)
            }
        } else {
            if (domainAnchor != null) {
                blocklistDomainRules.computeIfAbsent(domainAnchor) { mutableListOf() }.add(compiled)
            } else {
                blocklistGenericRules.add(compiled)
            }
        }
    }

    override fun evaluate(request: FilterRequest): FilterResult {
        val host = request.host

        // 1. Lookalike & Homoglyph Attack Defense
        // If the host is an impostor domain or contains homoglyphs mimicking trusted domains, block it immediately!
        if (LookalikeDetector.isHomographOrPunycode(host)) {
            val normalized = LookalikeDetector.normalizeHomoglyphs(host)
            for (trusted in trustedDomains) {
                if (normalized == trusted || normalized.endsWith(".$trusted")) {
                    return FilterResult.blocked(
                        rule = "LOOKALIKE_HOMOGLYPH_ATTACK",
                        reason = "Blocked suspicious IDN/punycode homoglyph spoofing trusted domain: $trusted"
                    )
                }
            }
        }

        for (trusted in trustedDomains) {
            if (LookalikeDetector.isImpostorDomain(host, trusted)) {
                return FilterResult.blocked(
                    rule = "LOOKALIKE_IMPOSTOR_ATTACK",
                    reason = "Blocked lookalike impostor domain masquerading as trusted domain: $trusted"
                )
            }
        }

        // 2. Exception / Allowlist Evaluation (Highest Precedence)
        val matchedAllowlistRule = checkRules(request, allowlistDomainRules, allowlistGenericRules)
        if (matchedAllowlistRule != null) {
            return FilterResult.allowlisted(
                rule = matchedAllowlistRule.rawRule,
                reason = "Request matched exception allowlist rule: ${matchedAllowlistRule.rawRule}"
            )
        }

        // 3. Blocklist Evaluation
        val matchedBlockRule = checkRules(request, blocklistDomainRules, blocklistGenericRules)
        if (matchedBlockRule != null) {
            return FilterResult.blocked(
                rule = matchedBlockRule.rawRule,
                reason = "Request matched blocking rule: ${matchedBlockRule.rawRule}"
            )
        }

        return FilterResult.allowed()
    }

    private fun checkRules(
        request: FilterRequest,
        domainRulesMap: Map<String, List<CompiledRule>>,
        genericRules: List<CompiledRule>
    ): CompiledRule? {
        val host = request.host

        // Check host and all subdomain suffixes (e.g. "a.b.example.com", "b.example.com", "example.com")
        val candidateDomains = generateDomainHierarchy(host)
        for (domain in candidateDomains) {
            val rules = domainRulesMap[domain]
            if (rules != null) {
                for (rule in rules) {
                    if (matchesRule(rule, request)) {
                        return rule
                    }
                }
            }
        }

        // Check generic rules
        for (rule in genericRules) {
            if (matchesRule(rule, request)) {
                return rule
            }
        }

        return null
    }

    private fun matchesRule(rule: CompiledRule, request: FilterRequest): Boolean {
        // Check audio specificity: If this is an audio playback request and rule does not explicitly match media, skip
        if (request.requestType == RequestType.AUDIO_PLAYBACK && !rule.appliesToAudio) {
            // General adblock rules do not blindly apply to audio playback requests
            return false
        }

        // Third-party check if requested
        if (rule.isThirdPartyOnly && request.sourceUrl != null) {
            val sourceHost = FilterRequest(request.sourceUrl, RequestType.OTHER).host
            if (isSameParty(request.host, sourceHost)) {
                return false
            }
        }

        // Regex / path pattern check
        if (rule.regex != null) {
            val matcher = rule.regex.matcher(request.url)
            return matcher.find()
        }

        // Exact path check
        if (rule.exactPath != null) {
            return request.path.startsWith(rule.exactPath)
        }

        // Domain anchor matched host without additional path constraints
        return true
    }

    private fun generateDomainHierarchy(host: String): List<String> {
        val results = mutableListOf<String>()
        val parts = host.split('.')
        for (i in 0 until parts.size - 1) {
            results.add(parts.subList(i, parts.size).joinToString("."))
        }
        return results
    }

    private fun isSameParty(host1: String, host2: String): Boolean {
        val apex1 = LookalikeDetector.getApexDomain(host1)
        val apex2 = LookalikeDetector.getApexDomain(host2)
        return apex1.isNotEmpty() && apex1.equals(apex2, ignoreCase = true)
    }

    private fun compilePatternToRegex(pattern: String): Pattern {
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when (c) {
                '*' -> sb.append(".*")
                '^' -> sb.append("(?:[^a-zA-Z0-9_\\-.%]|$)")
                '|' -> {
                    if (i == 0) sb.append("^")
                    else if (i == pattern.length - 1) sb.append("$")
                    else sb.append("\\|")
                }
                '.', '?', '+', '(', ')', '[', ']', '{', '}', '\\', '$' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE)
    }
}
