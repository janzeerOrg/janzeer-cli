package org.janzeer.jz.out

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.janzeer.jz.JzContext
import org.janzeer.jz.platform.Platform

/** Shared renderers for the node's objects: one place decides how a tx, a block or an amount looks in a table. */
object Render {
    fun jnz(ctx: JzContext, v: JsonElement?): String = v.str()?.let { runCatching { ctx.keys.formatJnz(it) }.getOrDefault("$it JNZ") } ?: "-"
    fun time(ms: Long?): String = ms?.let { "${Platform.isoUtc(it).replace("T", " ").removeSuffix("Z").take(19)} UTC  (${relativeAge(it, Platform.nowMs())})" } ?: "-"

    /** Compact one-line description of a transaction's action. */
    fun txWhat(ctx: JzContext, t: JsonElement?): String = when (t.str("type")) {
        "transfer" -> "${jnz(ctx, t["amount"])} → ${t.str("recipientAddress")?.short(8, 4).orDash()}" + (t.str("data")?.takeIf { it.isNotEmpty() }?.let { "  \"${it.take(24)}\"" } ?: "")
        "reward" -> "reward ${jnz(ctx, t["amount"])} → ${t.str("recipientAddress")?.short(8, 4).orDash()}"
        "registerValidator" -> "register validator ${t.str("validatorKey")?.short(8, 4).orDash()}  deposit ${jnz(ctx, t["amount"])}"
        "exitValidator" -> "exit validator ${t.str("validatorKey")?.short(8, 4).orDash()}"
        "token" -> "token ${t.str("op")?.lowercase().orDash()} ${t.str("symbol") ?: t.str("tokenId")?.short(6, 4).orDash()}" + (t.str("tokenAmount")?.let { " $it" } ?: "") + (t.str("recipient")?.let { " → ${it.short(8, 4)}" } ?: "")
        "slashing" -> "slashing ${t.str("offenderKey")?.short(8, 4).orDash()}"
        else -> t.str("type").orDash()
    }

    fun txRow(ctx: JzContext, t: JsonElement?): List<String> = listOf(
        t.str("hash")?.short(10, 6).orDash(), t.str("type").orDash(), t.str("status").orDash(), t.long("blockHeight")?.let { "#$it" } ?: "-",
        t.str("senderAddress")?.short(8, 4).orDash(), txWhat(ctx, t), jnz(ctx, t["fee"]),
    )
    val txHeader = listOf("hash", "type", "status", "block", "from", "what", "fee")

    fun txDetail(ctx: JzContext, t: JsonElement?): List<Pair<String, String>> = buildList {
        add("hash" to t.str("hash").orDash()); add("type" to t.str("type").orDash()); add("status" to t.str("status").orDash())
        add("block" to (t.long("blockHeight")?.let { "#$it  ${t.str("blockHash")?.short(10, 6).orDash()}" } ?: "pending"))
        add("time" to time(t.long("timestamp"))); add("from" to t.str("senderAddress").orDash()); add("nonce" to t.long("nonce").orDash())
        when (t.str("type")) {
            "transfer", "reward" -> { add("to" to t.str("recipientAddress").orDash()); add("amount" to jnz(ctx, t["amount"])); t.str("data")?.takeIf { it.isNotEmpty() }?.let { add("memo" to it) } }
            "registerValidator" -> { add("validator key" to t.str("validatorKey").orDash()); add("deposit" to jnz(ctx, t["amount"])) }
            "exitValidator" -> add("validator key" to t.str("validatorKey").orDash())
            "token" -> { add("op" to t.str("op").orDash()); add("token" to (t.str("symbol")?.let { "$it  " } ?: "") + t.str("tokenId").orDash()); t.str("tokenAmount")?.let { add("token amount" to it) }; t.str("recipient")?.let { add("recipient" to it) }; t.str("name")?.let { add("name" to it) }; t.int("decimals")?.let { add("decimals" to "$it") }; t.str("cap")?.let { add("cap" to it) } }
        }
        add("fee" to jnz(ctx, t["fee"]))
        t["receipt"]?.let { r -> add("receipt" to if (r.bool("successful") == true) "successful" else "FAILED") }
        t.str("senderPublicKey")?.let { add("sender key" to it) }
        t.str("senderSignature")?.let { add("signature" to it) }
    }

    fun blockDetail(ctx: JzContext, b: JsonElement?): List<Pair<String, String>> = listOf(
        "height" to "#${b.long("height").orDash()}  (${b.str("type").orDash()} block, epoch ${b.long("epochIndex").orDash()})",
        "hash" to b.str("hash").orDash(), "previous" to b.str("previousHash").orDash(), "time" to time(b.long("timestamp")),
        "producer" to b.str("producer").orDash(), "transactions" to b.long("transactionsCount").orDash(),
        "tx root" to b.str("transactionMerkleHash")?.short(16, 8).orDash(), "state root" to b.str("stateMerkleHash")?.short(16, 8).orDash(), "receipt root" to b.str("receiptMerkleHash")?.short(16, 8).orDash(),
    )

    fun blockRow(b: JsonElement?): List<String> = listOf("#${b.long("height").orDash()}", b.str("hash")?.short(10, 6).orDash(), Platform.isoUtc(b.long("timestamp") ?: 0).take(19).replace("T", " "), b.str("producer")?.short(8, 4).orDash(), b.long("transactionsCount").orDash())
    val blockHeader = listOf("height", "hash", "time (UTC)", "producer", "txs")

    /** Any object as key/value rows (fallback when the shape is open-ended, e.g. token metadata). */
    fun generic(o: JsonElement?): List<Pair<String, String>> = (o as? JsonObject)?.entries?.filter { it.value is JsonPrimitive }?.map { it.key to it.value.str().orDash() } ?: emptyList()

    /** Table from a list of objects using the first present keys of each candidate group. */
    fun genericTable(list: JsonArray, columns: List<Pair<String, List<String>>>): Pair<List<String>, List<List<String>>> {
        val header = columns.map { it.first }
        val rows = list.map { o -> columns.map { (_, keys) -> keys.firstNotNullOfOrNull { k -> o.str(k) }.orDash() } }
        return header to rows
    }

    private fun Any?.orDash(): String = this?.toString() ?: "-"

    fun pageParams(page: Int, size: Int): JsonArray = buildJsonArray { add(JsonPrimitive(page)); add(JsonPrimitive(size)) }
    fun pageSummary(p: JsonElement?): String { val total = p.long("total") ?: 0; return if (total == 0L) "none" else "page ${p.long("page")?.plus(1).orDash()} of ${p.long("totalPages").orDash()}  ·  $total total" }
    fun wrap(vararg pairs: Pair<String, JsonElement?>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v ?: kotlinx.serialization.json.JsonNull) } }
}
