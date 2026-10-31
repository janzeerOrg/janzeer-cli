package org.janzeer.jz.sdk

import org.janzeer.jz.ExitCodes
import org.janzeer.jz.JzException
import org.janzeer.sdk.client.ApiException
import org.janzeer.sdk.client.FinalityTimeoutException
import org.janzeer.sdk.client.JanzeerException
import org.janzeer.sdk.client.NetworkException
import org.janzeer.sdk.client.NonceMismatchException
import org.janzeer.sdk.client.NotFoundException
import org.janzeer.sdk.client.NotSynchronizedException
import org.janzeer.sdk.client.RateLimitedException
import org.janzeer.sdk.client.RpcException
import org.janzeer.sdk.client.RpcInvalidParamsException
import org.janzeer.sdk.client.RpcMethodNotFoundException
import org.janzeer.sdk.client.RpcNotFoundException
import org.janzeer.sdk.client.TxRejectedException
import org.janzeer.sdk.client.ValidationException
import org.janzeer.sdk.vault.VaultException

/** SDK exception → exit code + stable kind. Keyed on the class, never on message text (the SDK's own rule). */
object Errors {
    fun map(e: Throwable): JzException = when (e) {
        is JzException -> e
        is NonceMismatchException -> JzException(ExitCodes.TX_REJECTED, "nonce-mismatch", e.message ?: "nonce mismatch",
            if (e.expected != null) "the node expects nonce ${e.expected}; drop --nonce or pass --nonce ${e.expected}" else null, e, e.expected)
        is TxRejectedException -> JzException(ExitCodes.TX_REJECTED, "tx-rejected", "${e.type}: ${e.message}", null, e)
        is ValidationException -> JzException(ExitCodes.TX_REJECTED, "validation", e.message ?: "validation failed", null, e)
        is NotFoundException, is RpcNotFoundException -> JzException(ExitCodes.NOT_FOUND, "not-found", e.message ?: "not found", null, e)
        is RpcInvalidParamsException -> JzException(ExitCodes.TX_REJECTED, "invalid-params", e.message ?: "invalid params", null, e)
        is RpcMethodNotFoundException -> JzException(ExitCodes.UNEXPECTED, "rpc-method", e.message ?: "method not found", "the node may be older than this jz", e)
        is RateLimitedException -> JzException(ExitCodes.NODE_BUSY, "rate-limited", e.message ?: "rate limited", "retry after ${e.retryAfterMs} ms", e)
        is NotSynchronizedException -> JzException(ExitCodes.NODE_BUSY, "not-synchronized", e.message ?: "node not synchronized", "the node is still catching up; try again in a minute", e)
        is FinalityTimeoutException -> JzException(ExitCodes.FINALITY_TIMEOUT, "finality-timeout", e.message ?: "not final in time",
            "jz tx ${e.hash} --wait to keep waiting; last status: ${e.last?.status?.wireName ?: "unknown"}", e)
        is NetworkException -> JzException(ExitCodes.NETWORK, "network", e.message ?: "network error", "check --node / the profile and that the node is up", e)
        is ApiException -> JzException(ExitCodes.UNEXPECTED, "api", "HTTP ${e.status}: ${e.message}", null, e)
        is RpcException -> JzException(ExitCodes.UNEXPECTED, "rpc", "RPC ${e.code}: ${e.message}", null, e)
        is VaultException -> JzException(ExitCodes.WALLET, "vault", e.message ?: "vault error", null, e)
        is JanzeerException -> JzException(ExitCodes.UNEXPECTED, "sdk", e.message ?: e.toString(), null, e)
        is InterruptedException -> JzException(ExitCodes.INTERRUPTED, "interrupted", "interrupted", null, e)
        is IllegalArgumentException -> JzException(ExitCodes.USAGE, "invalid", e.message ?: "invalid input", null, e)
        else -> JzException(ExitCodes.UNEXPECTED, "unexpected", e.toString(), null, e)
    }

    inline fun <T> wrap(block: () -> T): T = try { block() } catch (e: Throwable) { throw map(e) }
}
