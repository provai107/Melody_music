package com.braveplayer.filter.engine

import java.net.IDN

/**
 * Detects deceptive lookalike domains, internationalized homograph attacks,
 * punycode obfuscation, and spoofed audio CDN hostnames.
 */
object LookalikeDetector {

    // Common Cyrillic and Greek homoglyphs mimicking Latin characters
    private val HOMOGLYPH_MAP = mapOf(
        'а' to 'a', 'а' to 'a', // Cyrillic small letter a (U+0430)
        'с' to 'c', // Cyrillic small letter es (U+0441)
        'е' to 'e', // Cyrillic small letter ie (U+0435)
        'і' to 'i', // Cyrillic small letter byelorussian-ukrainian i (U+0456)
        'ј' to 'j', // Cyrillic small letter je (U+0458)
        'о' to 'o', // Cyrillic small letter o (U+043E)
        'р' to 'p', // Cyrillic small letter er (U+0440)
        'ѕ' to 's', // Cyrillic small letter dze (U+0455)
        'у' to 'y', // Cyrillic small letter u (U+0443)
        'х' to 'x', // Cyrillic small letter ha (U+0445)
        'ν' to 'v', // Greek small letter nu (U+03BD)
        'ο' to 'o'  // Greek small letter omicron (U+03BF)
    )

    /**
     * Checks if a domain contains homoglyph/confusable characters or IDN punycode.
     */
    fun isHomographOrPunycode(domain: String): Boolean {
        val lower = domain.lowercase()
        if (lower.startsWith("xn--") || lower.contains(".xn--")) {
            return true
        }

        // Check if characters contain non-ASCII homoglyphs
        for (char in lower) {
            if (char > 127.toChar() && HOMOGLYPH_MAP.containsKey(char)) {
                return true
            }
        }
        return false
    }

    /**
     * Normalizes a potentially deceptive domain by converting homoglyphs to standard Latin.
     */
    fun normalizeHomoglyphs(domain: String): String {
        val decoded = try {
            if (domain.contains("xn--")) IDN.toUnicode(domain) else domain
        } catch (_: Exception) {
            domain
        }

        val sb = StringBuilder()
        for (ch in decoded.lowercase()) {
            sb.append(HOMOGLYPH_MAP[ch] ?: ch)
        }
        return sb.toString()
    }

    /**
     * Checks if a domain is an impostor attempting to spoof a legitimate target domain
     * (e.g., "googlevideo.com.attacker.com" posing as "googlevideo.com", or "spotify-audio.co").
     */
    fun isImpostorDomain(candidateHost: String, trustedBaseDomain: String): Boolean {
        val candidate = normalizeHomoglyphs(candidateHost).lowercase()
        val trusted = trustedBaseDomain.lowercase()

        if (candidate == trusted || candidate.endsWith(".$trusted")) {
            // Legitimate host or true subdomain of trusted base
            return false
        }

        // Deceptive spoofing: candidate domain embeds trusted domain name before attacker's root
        // Example: googlevideo.com.attacker.com or api-spotify.com.fraud.io
        if (candidate.contains("$trusted.") || candidate.contains("${trusted.replace(".", "-")}.")) {
            return true
        }

        // Check Levenshtein distance on apex domain if names are very similar but not equal
        val candidateApex = getApexDomain(candidate)
        val trustedApex = getApexDomain(trusted)
        if (candidateApex != trustedApex && isTypoSquat(candidateApex, trustedApex)) {
            return true
        }

        return false
    }

    /**
     * Extracts apex domain (e.g., "audio.cdn.example.com" -> "example.com").
     */
    fun getApexDomain(host: String): String {
        val parts = host.split('.')
        return if (parts.size >= 2) {
            "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
        } else {
            host
        }
    }

    /**
     * Determines whether [candidate] is a 1-character typo-squat of [target].
     */
    fun isTypoSquat(candidate: String, target: String): Boolean {
        if (Math.abs(candidate.length - target.length) > 1) return false
        val distance = levenshteinDistance(candidate, target)
        return distance == 1
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
