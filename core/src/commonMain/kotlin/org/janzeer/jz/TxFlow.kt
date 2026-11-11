package org.janzeer.jz

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.janzeer.jz.out.Render
import org.janzeer.jz.out.get
import org.janzeer.jz.out.str

/**
 * The one path every write takes: open the wallet → network id from the node → nonce → fee → build + sign (SDK) →
 * show → confirm → POST → optionally wait for finality. A nonce mismatch is retried once with the node's expected nonce.
 */
object TxFlow {
    /** What a command wants sent. [fields] are the SDK builder inputs without from/nonce/fee/networkId. */
    class Request(
        val kind: String,
        /** `janzeer_estimateFee` kind: transfer | token | tokenCreate | registerValidator | exitValidator */
        val feeKind: String,
        val fields: JsonObject,
        /** Human summary lines shown before the confirmation. */
        val summary: List<Pair<String, String>>,
        /** Extra warning (e.g. a non-refundable deposit) shown in bold before the confirmation. */
        val warning: String? = null,
        /** Word the user must type instead of a plain y/N (irreversible actions). */
        val typedWord: String? = null,
    )

    class Options(val wallet: String?, val fee: String?, val nonce: Long?, val wait: Boolean, val waitTimeoutSec: Int, val dryRun: Boolean)

    fun run(ctx: JzContext, req: Request, o: Options) {
        val w = ctx.wallets.load(o.wallet ?: ctx.settings.defaultWallet)
        o.fee?.let { if (!ctx.keys.isValidAmount(it)) throw JzException(ExitCodes.USAGE, "usage", "--fee must be a decimal amount, got '$it'") }

        val info = ctx.chain.get("info")
        ctx.checkNode(info)
        val networkId = info.str("networkId") ?: throw JzException(ExitCodes.NETWORK, "network", "node did not report its network id")

        // Fee: the node's estimate is authoritative when the rule is exact; a minimum may be raised by --fee.
        val est = ctx.chain.rpc("janzeer_estimateFee", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(req.feeKind)) })
        val estFee = est.str("fee")?.let { ctx.keys.normalizeAmount(it) }
        val rule = est.str("rule") ?: "minimum"
        val fee = when {
            estFee == null -> o.fee ?: throw JzException(ExitCodes.NETWORK, "fee", "node returned no fee estimate; pass --fee")
            rule == "exact" -> { if (o.fee != null && ctx.keys.compareAmounts(o.fee, estFee) != 0) ctx.out.warn("fee for ${req.feeKind} is fixed at ${ctx.keys.formatJnz(estFee)}; --fee ignored"); estFee }
            o.fee == null -> estFee
            ctx.keys.compareAmounts(o.fee, estFee) < 0 -> { ctx.out.warn("--fee ${o.fee} is below the minimum ${ctx.keys.formatJnz(estFee)}; using the minimum"); estFee }
            else -> ctx.keys.normalizeAmount(o.fee)
        }

        // The secret is needed from here on.
        val (_, key) = Prompts.unlock(ctx, w)
        var nonce = o.nonce ?: nextNonce(ctx, key.address)

        fun build(n: Long) = ctx.keys.buildAndSign(req.kind, buildJsonObject {
            req.fields.forEach { (k, v) -> put(k, v) }
            put("from", key.address); put("nonce", n); put("fee", fee); put("networkId", networkId)
        }, key.privateKeyHex)
        var tx = build(nonce)

        val summary = listOf("from" to key.checksumAddress + "  (wallet ${w.name})") + req.summary + listOf("fee" to ctx.keys.formatJnz(fee), "nonce" to "$nonce", "network" to networkId, "hash" to tx.hash)
        if (o.dryRun) {
            ctx.out.data(buildJsonObject { put("path", tx.restPath); put("hash", tx.hash); put("body", tx.body) })
            ctx.out.line("dry run — nothing sent. REST body for POST /api/v1/${tx.restPath}:")
            ctx.out.kv(summary)
            if (!ctx.out.json) ctx.out.scalarRaw(kotlinx.serialization.json.Json { prettyPrint = true; prettyPrintIndent = "  " }.encodeToString(JsonObject.serializer(), tx.body))
            return
        }
        ctx.out.kv(summary)
        req.warning?.let { ctx.out.line(); ctx.out.line(ctx.out.bold(it)) }
        if (req.typedWord != null) Prompts.typed(ctx, req.typedWord, "Confirm") else if (!Prompts.confirm(ctx, "Send this transaction?")) throw JzException(ExitCodes.USAGE, "cancelled", "cancelled")

        var echo = try { ctx.chain.post(tx.restPath, tx.body) } catch (e: JzException) {
            val expected = (e.extra as? Long)?.takeIf { e.kind == "nonce-mismatch" && o.nonce == null }
            if (expected == null) throw e
            ctx.out.warn("nonce $nonce refused, the node expects $expected — rebuilt and resent once")
            nonce = expected; tx = build(nonce)
            ctx.chain.post(tx.restPath, tx.body)
        }
        if (!o.wait) {
            ctx.out.data(buildJsonObject { put("hash", tx.hash); put("status", echo.str("status") ?: "PENDING"); put("accepted", echo ?: kotlinx.serialization.json.JsonNull) })
            ctx.out.success("accepted by the node — hash ${tx.hash}")
            ctx.out.line(ctx.out.dim("jz tx ${tx.hash} --wait   to follow it to finality"))
            return
        }
        ctx.out.line(ctx.out.dim("accepted, waiting for finality (up to ${o.waitTimeoutSec} s)…"))
        val fin = ctx.chain.waitForFinality(tx.hash, o.waitTimeoutSec * 1000L)
        ctx.out.data(fin)
        ctx.out.success("FINAL in block #${fin["blockHeight"].str() ?: "?"}")
        ctx.out.kv(Render.txDetail(ctx, fin).filter { it.first in setOf("hash", "block", "time", "fee", "receipt") })
    }

    private fun nextNonce(ctx: JzContext, address: String): Long =
        ctx.chain.rpc("janzeer_getNonce", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(address)) }).str()?.toLongOrNull()
            ?: throw JzException(ExitCodes.NETWORK, "nonce", "node returned no nonce for $address")
}
