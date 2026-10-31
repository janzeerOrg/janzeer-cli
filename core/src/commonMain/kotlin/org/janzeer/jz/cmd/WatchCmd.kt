package org.janzeer.jz.cmd

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.janzeer.jz.ExitCodes
import org.janzeer.jz.JzContext
import org.janzeer.jz.JzException
import org.janzeer.jz.out.Render
import org.janzeer.jz.out.arr
import org.janzeer.jz.out.get
import org.janzeer.jz.out.long
import org.janzeer.jz.out.short
import org.janzeer.jz.out.str
import org.janzeer.jz.platform.Platform

/** Live feeds over the node's WebSocket. One line per event; `--json` prints one JSON object per line (NDJSON). Ctrl-C stops. */
class WatchCmd : CliktCommand(name = "watch") {
    override fun help(context: Context) = "Live feed over WebSocket: `blocks` or `address ADDR…`; one line per event, Ctrl-C to stop"
    init { subcommands(Blocks(), Address()) }
    override fun run() = Unit

    class Blocks : CliktCommand(name = "blocks") {
        override fun help(context: Context) = "Every committed block as it lands"
        private val ctx by requireObject<JzContext>()
        private val from by option("--from", metavar = "HEIGHT", help = "Replay from this height first").long()
        private val txs by option("--txs", help = "Include each block's transactions").flag()
        override fun run() {
            val params = buildJsonObject { if (from != null) put("fromHeight", from); put("includeTransactions", txs) }
            watch(ctx, "newBlocks", params) { b ->
                ctx.out.line("#${b.long("height")}  ${b.str("hash")?.short(10, 6)}  ${Platform.isoUtc(b.long("timestamp") ?: 0).substring(11, 19)}  producer ${b.str("producer")?.short(8, 4)}  ${b.long("transactionsCount") ?: 0} tx")
                if (txs) b["transactions"].arr()?.forEach { t -> ctx.out.line("    ${t.str("hash")?.short(10, 6)}  ${t.str("type")}  ${Render.txWhat(ctx, t)}") }
            }
        }
    }

    class Address : CliktCommand(name = "address") {
        override fun help(context: Context) = "Transactions touching the given addresses, as their blocks commit"
        private val ctx by requireObject<JzContext>()
        private val addresses by argument("ADDRESS").multiple(required = true)
        override fun run() {
            addresses.forEach { if (!ctx.keys.isValidAddress(it)) throw JzException(ExitCodes.USAGE, "address", "not a valid address: $it") }
            val params = buildJsonObject { put("addresses", buildJsonArray { addresses.forEach { add(JsonPrimitive(it.lowercase())) } }) }
            watch(ctx, "addressActivity", params) { e ->
                val t = e["transaction"]
                ctx.out.line("#${e.long("blockHeight")}  ${t.str("hash")?.short(10, 6)}  ${t.str("type")}  from ${t.str("senderAddress")?.short(8, 4)}  ${Render.txWhat(ctx, t)}")
            }
        }
    }

    companion object {
        private val ndjson = Json
        fun watch(ctx: JzContext, kind: String, params: kotlinx.serialization.json.JsonObject, human: (JsonElement) -> Unit) {
            val handle = ctx.chain.subscribe(kind, params, onEvent = { e ->
                if (ctx.out.json) ctx.out.scalarRaw(ndjson.encodeToString(JsonElement.serializer(), e)) else human(e)
            }, onStatus = { s -> ctx.out.debug("ws $s") })
            Platform.onShutdown { runCatching { handle.close() } }
            if (!ctx.out.json) ctx.out.line(ctx.out.dim("watching $kind on ${ctx.settings.profile.node} — Ctrl-C to stop"))
            Platform.waitForever()
        }
    }
}
