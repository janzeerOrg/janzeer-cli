package org.janzeer.jz.cmd

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.janzeer.jz.ExitCodes
import org.janzeer.jz.JzContext
import org.janzeer.jz.JzException
import org.janzeer.jz.out.Render
import org.janzeer.jz.out.arr
import org.janzeer.jz.out.bool
import org.janzeer.jz.out.get
import org.janzeer.jz.out.int
import org.janzeer.jz.out.long
import org.janzeer.jz.out.orDash
import org.janzeer.jz.out.short
import org.janzeer.jz.out.str

private fun params(vararg v: Any?): JsonArray = buildJsonArray { v.forEach { add(when (it) { null -> kotlinx.serialization.json.JsonNull; is Number -> JsonPrimitive(it); is Boolean -> JsonPrimitive(it); else -> JsonPrimitive(it.toString()) }) } }
private fun JzContext.requireAddress(a: String): String { if (!keys.isValidAddress(a)) throw JzException(ExitCodes.USAGE, "address", "not a valid address: $a"); return a.lowercase() }
private fun isHash(s: String) = s.removePrefix("0x").length == 64 && s.removePrefix("0x").all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

class BlockCmd : CliktCommand(name = "block") {
    override fun help(context: Context) = "One block by height, hash or `tip`; --txs lists its transactions"
    private val ctx by requireObject<JzContext>()
    private val ref by argument("HEIGHT|HASH|tip")
    private val txs by option("--txs", help = "Include the transactions").flag()
    override fun run() {
        val b = when {
            ref == "tip" -> ctx.chain.rpc("janzeer_getTip").let { tip -> if (txs) ctx.chain.rpc("janzeer_getBlockByHash", params(tip.str("hash"), true)) else tip }
            ref.toLongOrNull() != null -> ctx.chain.rpc("janzeer_getBlockByNumber", params(ref.toLong(), txs))
            isHash(ref) -> ctx.chain.rpc("janzeer_getBlockByHash", params(ref, txs))
            else -> throw JzException(ExitCodes.USAGE, "usage", "expected a height, a 64-hex hash or 'tip'")
        } ?: throw JzException(ExitCodes.NOT_FOUND, "not-found", "no block $ref")
        ctx.out.data(b)
        ctx.out.kv(Render.blockDetail(ctx, b))
        b["transactions"].arr()?.let { list -> ctx.out.line(); ctx.out.table(Render.txHeader, list.map { Render.txRow(ctx, it) }) }
    }
}

class TxCmd : CliktCommand(name = "tx") {
    override fun help(context: Context) = "One transaction by hash; --wait blocks until it is final (exit 7 on timeout)"
    private val ctx by requireObject<JzContext>()
    private val hash by argument("HASH")
    private val wait by option("--wait", help = "Wait for finality").flag()
    private val waitTimeout by option("--wait-timeout", metavar = "SEC", help = "Give up after this long").int().default(180)
    override fun run() {
        if (!isHash(hash)) throw JzException(ExitCodes.USAGE, "usage", "expected a 64-hex transaction hash")
        val t = if (wait) ctx.chain.waitForFinality(hash, waitTimeout * 1000L)
        else ctx.chain.rpc("janzeer_getTransactionByHash", params(hash))
        if (t == null || t.str("status") == "UNKNOWN") throw JzException(ExitCodes.NOT_FOUND, "not-found", "no transaction $hash", "a pending transaction appears once a node has it; a final one is on the explorer")
        ctx.out.data(t)
        ctx.out.kv(Render.txDetail(ctx, t))
    }
}

class AddressCmd : CliktCommand(name = "address") {
    override fun help(context: Context) = "Balance, nonces and pending count of an address; --tokens and --txs add JZT-1 balances and history"
    private val ctx by requireObject<JzContext>()
    private val address by argument("ADDRESS")
    private val txs by option("--txs", help = "Also list transactions").flag()
    private val tokens by option("--tokens", help = "Also list JZT-1 token balances").flag()
    private val page by option("--page", help = "Page (1-based) for --txs / --tokens").int().default(1)
    private val size by option("--size", help = "Rows per page").int().default(20)
    override fun run() {
        val a = ctx.requireAddress(address)
        val acc = ctx.chain.rpc("janzeer_getAccount", params(a))
        val tok = if (tokens) ctx.chain.rpc("janzeer_getTokenBalances", params(a, page - 1, size)) else null
        val hist = if (txs) ctx.chain.rpc("janzeer_getTransactionsByAddress", params(a, page - 1, size)) else null
        ctx.out.data(Render.wrap("account" to acc, "tokens" to tok, "transactions" to hist))
        ctx.out.kv(listOf(
            "address" to ctx.keys.checksumAddress(a),
            "balance" to Render.jnz(ctx, acc["balance"]) + (acc.str("committedBalance")?.takeIf { it != acc.str("balance") }?.let { "   (committed ${Render.jnz(ctx, acc["committedBalance"])})" } ?: ""),
            "next nonce" to "${acc.long("nextNonce").orDashS()}" + (acc.long("committedNonce")?.let { "   (committed $it)" } ?: ""),
            "pending" to "${acc.int("pendingCount") ?: 0} transaction(s)",
            "validator wallet" to if (acc.bool("isValidatorWallet") == true) "yes" else "no",
        ))
        tok?.let { p ->
            ctx.out.line(); ctx.out.line("Tokens  " + Render.pageSummary(p))
            val (h, rows) = Render.genericTable(p["list"].arr() ?: JsonArray(emptyList()), listOf("token" to listOf("tokenId", "id"), "symbol" to listOf("symbol"), "balance" to listOf("balance", "amount"), "decimals" to listOf("decimals")))
            ctx.out.table(h, rows)
        }
        hist?.let { p ->
            ctx.out.line(); ctx.out.line("Transactions  " + Render.pageSummary(p))
            ctx.out.table(Render.txHeader, (p["list"].arr() ?: JsonArray(emptyList())).map { Render.txRow(ctx, it) })
            p["pending"].arr()?.takeIf { it.isNotEmpty() }?.let { pend -> ctx.out.line(); ctx.out.line("Pending"); ctx.out.table(Render.txHeader, pend.map { Render.txRow(ctx, it) }) }
        }
    }
    private fun Long?.orDashS() = this?.toString() ?: "-"
}

class BalanceCmd : CliktCommand(name = "balance") {
    override fun help(context: Context) = "Spendable balance of an address, one value (for scripts)"
    private val ctx by requireObject<JzContext>()
    private val address by argument("ADDRESS")
    override fun run() { ctx.out.scalar(ctx.chain.rpc("janzeer_getBalance", params(ctx.requireAddress(address))).str() ?: "0") }
}

class NonceCmd : CliktCommand(name = "nonce") {
    override fun help(context: Context) = "The nonce to sign the NEXT transaction with, one value"
    private val ctx by requireObject<JzContext>()
    private val address by argument("ADDRESS")
    override fun run() { ctx.out.scalar(ctx.chain.rpc("janzeer_getNonce", params(ctx.requireAddress(address))).str() ?: "0") }
}

class ValidatorsCmd : CliktCommand(name = "validators") {
    override fun help(context: Context) = "Registered validators (--active: the current producer set); `show KEY` for one"
    override val invokeWithoutSubcommand = true
    init { subcommands(Show()) }
    private val ctx by requireObject<JzContext>()
    private val active by option("--active", help = "Only the active set").flag()
    private val page by option("--page").int().default(1)
    private val size by option("--size").int().default(50)
    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        val r = if (active) ctx.chain.rpc("janzeer_getActiveValidators") else ctx.chain.rpc("janzeer_listValidators", params(page - 1, size))
        ctx.out.data(r ?: JsonArray(emptyList()))
        val list = (r as? JsonArray) ?: r["list"].arr() ?: JsonArray(emptyList())
        if (r !is JsonArray) ctx.out.line(Render.pageSummary(r))
        ctx.out.table(listOf("key", "wallet", "registered", "state"), list.map { v ->
            listOf(v.str("key").orDash(), v.str("walletAddress")?.short(10, 6).orDash(), v.long("registeredAt")?.let { org.janzeer.jz.platform.Platform.isoUtc(it).take(10) }.orDash(),
                when { v.bool("banned") == true -> "BANNED"; v.bool("exited") == true -> "exited"; v.bool("active") == true -> "active"; else -> "pending" })
        })
    }
    class Show : CliktCommand(name = "show") {
        override fun help(context: Context) = "One validator by its node key"
        private val ctx by requireObject<JzContext>()
        private val key by argument("KEY")
        override fun run() {
            val v = ctx.chain.rpc("janzeer_getValidator", params(key)) ?: throw JzException(ExitCodes.NOT_FOUND, "not-found", "no validator $key")
            ctx.out.data(v)
            ctx.out.kv(listOf("key" to v.str("key").orDash(), "wallet" to v.str("walletAddress").orDash(), "registered" to Render.time(v.long("registeredAt")),
                "active" to (v.bool("active")?.toString() ?: "-"), "banned" to (v.bool("banned")?.toString() ?: "-"), "exited" to (v.bool("exited")?.toString() ?: "-")))
        }
    }
}

class TokensCmd : CliktCommand(name = "tokens") {
    override fun help(context: Context) = "JZT-1 tokens: list, `show ID`, `holders ID`"
    override val invokeWithoutSubcommand = true
    init { subcommands(Show(), Holders()) }
    private val ctx by requireObject<JzContext>()
    private val page by option("--page").int().default(1)
    private val size by option("--size").int().default(20)
    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        val r = ctx.chain.rpc("janzeer_listTokens", params(page - 1, size))
        ctx.out.data(r ?: JsonArray(emptyList()))
        ctx.out.line(Render.pageSummary(r))
        val (h, rows) = Render.genericTable(r["list"].arr() ?: JsonArray(emptyList()), listOf("token" to listOf("tokenId", "id"), "symbol" to listOf("symbol"), "name" to listOf("name"), "decimals" to listOf("decimals"), "supply" to listOf("totalSupply", "supply"), "cap" to listOf("cap"), "owner" to listOf("owner", "creator", "ownerAddress")))
        ctx.out.table(h, rows)
    }
    class Show : CliktCommand(name = "show") {
        override fun help(context: Context) = "One token's metadata"
        private val ctx by requireObject<JzContext>()
        private val id by argument("TOKEN_ID")
        override fun run() {
            val t = ctx.chain.rpc("janzeer_getToken", params(id)) ?: throw JzException(ExitCodes.NOT_FOUND, "not-found", "no token $id")
            ctx.out.data(t); ctx.out.kv(Render.generic(t))
        }
    }
    class Holders : CliktCommand(name = "holders") {
        override fun help(context: Context) = "Addresses holding a token"
        private val ctx by requireObject<JzContext>()
        private val id by argument("TOKEN_ID")
        private val page by option("--page").int().default(1)
        private val size by option("--size").int().default(20)
        override fun run() {
            val r = ctx.chain.rpc("janzeer_getTokenHolders", params(id, page - 1, size))
            ctx.out.data(r ?: JsonArray(emptyList())); ctx.out.line(Render.pageSummary(r))
            val (h, rows) = Render.genericTable(r["list"].arr() ?: JsonArray(emptyList()), listOf("address" to listOf("address", "holder"), "balance" to listOf("balance", "amount")))
            ctx.out.table(h, rows)
        }
    }
}
