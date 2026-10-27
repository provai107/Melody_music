package com.braveplayer.filter.engine

import com.braveplayer.filter.model.FilterRequest
import com.braveplayer.filter.model.FilterResult

/**
 * Architectural bridge demonstrating how a native Rust `adblock-rust` engine binds
 * to the Android music player via JNI.
 *
 * Evaluation:
 * - As detailed in docs/BRAVE_ADBLOCK_RUST_ANALYSIS.md, bundling `adblock-rust` adds 15MB+
 *   to the APK per ABI and introduces JNI overhead across asynchronous Media3 chunk threads.
 * - This bridge implements [NetworkFilterEngine] and gracefully falls back to [BraveRulesFilterEngine]
 *   if the native library `libadblock_jni.so` is not bundled or fails to load.
 */
class NativeAdblockBridge(
    private val fallbackEngine: NetworkFilterEngine
) : NetworkFilterEngine {

    override val engineName: String
        get() = if (isNativeLoaded) "NativeAdblockRust(JNI)" else "NativeBridgeFallback(${fallbackEngine.engineName})"

    companion object {
        private var isNativeLoaded = false

        init {
            try {
                System.loadLibrary("adblock_jni")
                isNativeLoaded = true
            } catch (_: UnsatisfiedLinkError) {
                isNativeLoaded = false
            }
        }

        fun isNativeAvailable(): Boolean = isNativeLoaded
    }

    private var nativeEnginePtr: Long = 0

    fun initializeWithSerializedEngine(serializedData: ByteArray): Boolean {
        if (!isNativeLoaded) return false
        try {
            nativeEnginePtr = nativeInit(serializedData)
            return nativeEnginePtr != 0L
        } catch (_: Exception) {
            return false
        }
    }

    override fun evaluate(request: FilterRequest): FilterResult {
        if (isNativeLoaded && nativeEnginePtr != 0L) {
            try {
                val res = nativeCheckUrl(
                    nativeEnginePtr,
                    request.url,
                    request.sourceUrl ?: "",
                    request.requestType.name
                )
                return when {
                    res.isException -> FilterResult.allowlisted("NATIVE_EXCEPTION", "Allowlisted by adblock-rust")
                    res.isBlocked -> FilterResult.blocked("NATIVE_MATCH", "Blocked by adblock-rust")
                    else -> FilterResult.allowed()
                }
            } catch (_: Exception) {
                // Fail-safe fallback to Kotlin engine on unexpected native boundary failure
                return fallbackEngine.evaluate(request)
            }
        }
        return fallbackEngine.evaluate(request)
    }

    fun close() {
        if (isNativeLoaded && nativeEnginePtr != 0L) {
            nativeDestroy(nativeEnginePtr)
            nativeEnginePtr = 0L
        }
    }

    // JNI External Declarations
    private external fun nativeInit(flatbufferData: ByteArray): Long
    private external fun nativeCheckUrl(ptr: Long, url: String, sourceUrl: String, reqType: String): NativeResult
    private external fun nativeDestroy(ptr: Long)

    data class NativeResult(
        val isBlocked: Boolean,
        val isException: Boolean,
        val redirectUrl: String?
    )
}
