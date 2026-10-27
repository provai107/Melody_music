package com.braveplayer.filter.engine

import com.braveplayer.filter.model.FilterRequest
import com.braveplayer.filter.model.FilterResult

/**
 * Common contract for network filtering engines.
 * Implementations evaluate outgoing HTTP requests and decide whether to allow, allowlist, or block.
 */
interface NetworkFilterEngine {
    /**
     * Evaluates a network request against active filter rules and policies.
     */
    fun evaluate(request: FilterRequest): FilterResult

    /**
     * Engine name or implementation identifier.
     */
    val engineName: String
}
