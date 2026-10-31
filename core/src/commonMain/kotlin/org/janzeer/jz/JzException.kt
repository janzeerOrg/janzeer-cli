package org.janzeer.jz

/**
 * Every failure jz reports. [exitCode] is one of [ExitCodes]; [kind] is a stable machine name printed under `--json`
 * (e.g. `network`, `tx-rejected`); [hint] is an optional second line for humans. Adapters translate SDK exceptions into
 * this (jvmMain `Errors.kt`), commands throw it directly for their own refusals.
 */
class JzException(
    val exitCode: Int,
    val kind: String,
    message: String,
    val hint: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Process exit codes. Documented in README "Exit codes"; scripts rely on them. */
object ExitCodes {
    const val OK = 0
    const val UNEXPECTED = 1
    const val USAGE = 2
    const val NETWORK = 3
    const val NODE_BUSY = 4
    const val TX_REJECTED = 5
    const val NOT_FOUND = 6
    const val FINALITY_TIMEOUT = 7
    const val WALLET = 8
    const val NETWORK_MISMATCH = 9
    const val UPDATE_AVAILABLE = 10
    const val INTERRUPTED = 130
}
