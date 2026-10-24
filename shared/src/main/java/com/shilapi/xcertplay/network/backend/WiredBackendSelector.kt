package com.shilapi.xcertplay.network.backend

/** Persisted wired preference. AUTO keeps the installed release's established default. */
enum class WiredBackendPreference { AUTO, VPN, USERSPACE }

/** Backend selected for a single wired connection attempt. */
enum class WiredBackend { VPN, USERSPACE }

enum class WiredBackendFailure {
    VPN_UNAVAILABLE,
    USER_CANCELLED,
    CANCELLED,
    USB,
    MFI,
    PROTOCOL,
    NATIVE_UNAVAILABLE,
    INITIALIZATION,
}

/** A successful userspace probe is scoped to the stack build and process ABI that passed it. */
data class WiredBackendSuccessCache(val stackVersion: String, val abi: String) {
    fun matches(currentStackVersion: String, currentAbi: String): Boolean =
        stackVersion.isNotBlank() && abi.isNotBlank() &&
            stackVersion == currentStackVersion && abi == currentAbi
}

/** Per-connection selection state. Each backend can be attempted at most once. */
class WiredBackendSelector(
    private val preference: WiredBackendPreference,
    userspaceValidatedForRelease: Boolean,
    successCache: WiredBackendSuccessCache? = null,
    stackVersion: String = "",
    abi: String = "",
) {
    private val userspaceAllowedForAuto = userspaceValidatedForRelease ||
        successCache?.matches(stackVersion, abi) == true
    private val attempted = linkedSetOf<WiredBackend>()

    /** AUTO deliberately begins with the established VPN behavior. */
    @Synchronized fun begin(): WiredBackend? {
        if (attempted.isNotEmpty()) return null
        val first = firstAttempt(preference, userspaceValidatedForRelease = userspaceAllowedForAuto)
        attempted += first
        return first
    }

    /** Only a clearly unavailable VPN may cross the backend boundary, and only once in AUTO. */
    @Synchronized fun afterFailure(
        backend: WiredBackend,
        failure: WiredBackendFailure,
        cancelled: Boolean = false,
    ): WiredBackend? {
        if (cancelled || failure == WiredBackendFailure.CANCELLED || failure == WiredBackendFailure.USER_CANCELLED) return null
        if (backend !in attempted || preference != WiredBackendPreference.AUTO || backend != WiredBackend.VPN ||
            failure != WiredBackendFailure.VPN_UNAVAILABLE || !userspaceAllowedForAuto ||
            attempted.size >= MAX_ATTEMPTS || WiredBackend.USERSPACE in attempted) return null
        attempted += WiredBackend.USERSPACE
        return WiredBackend.USERSPACE
    }

    @Synchronized fun attemptedBackends(): List<WiredBackend> = attempted.toList()

    /** The latest selected attempt, including an allowed fallback; not proof of attachment. */
    @Synchronized fun currentBackend(): WiredBackend? = attempted.lastOrNull()

    companion object {
        const val MAX_ATTEMPTS = 2
        const val STACK_VERSION = "lwip-2.2.1-diplay.1"

        /** Initial selection never uses a saved success to interrupt a working VPN path. */
        @Suppress("UNUSED_PARAMETER")
        fun firstAttempt(
            preference: WiredBackendPreference,
            userspaceValidatedForRelease: Boolean,
        ): WiredBackend = when (preference) {
            WiredBackendPreference.VPN, WiredBackendPreference.AUTO -> WiredBackend.VPN
            WiredBackendPreference.USERSPACE -> WiredBackend.USERSPACE
        }
    }
}
