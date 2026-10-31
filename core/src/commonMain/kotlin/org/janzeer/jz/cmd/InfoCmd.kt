package org.janzeer.jz.cmd

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.janzeer.jz.JzContext
import org.janzeer.jz.out.get
import org.janzeer.jz.out.humanDuration
import org.janzeer.jz.out.int
import org.janzeer.jz.out.long
import org.janzeer.jz.out.orDash
import org.janzeer.jz.out.relativeAge
import org.janzeer.jz.out.short
import org.janzeer.jz.out.str
import org.janzeer.jz.platform.Platform

class InfoCmd : CliktCommand(name = "info") {
    override fun help(context: Context) = "Node and chain overview: network, versions, tip, epoch, peers, sync state"
    private val ctx by requireObject<JzContext>()

    override fun run() {
        val info = ctx.chain.get("info")
        ctx.checkNode(info)
        val sync = ctx.chain.get("info/sync")
        val rpc = ctx.chain.rpc("janzeer_getInfo")
        val epoch = ctx.chain.rpc("janzeer_getEpoch")
        ctx.out.data(buildJsonObject { put("info", info ?: JsonNull); put("sync", sync ?: JsonNull); put("rpc", rpc ?: JsonNull); put("epoch", epoch ?: JsonNull) })

        val tip = rpc["tip"]
        val tipTs = tip.long("timestamp")
        val slotMs = epoch.long("slotPeriodMs") ?: 15000
        val nextGenesis = epoch.long("nextGenesisHeight"); val height = tip.long("height")
        val nodeVersion = info.str("version")
        ctx.out.kv(listOf(
            "node" to ctx.settings.profile.node,
            "network" to (info.str("networkId").orDash() + "   genesis " + info.str("genesisHash")?.short().orDash()),
            "version" to "node ${nodeVersion.orDash()} · API ${info.str("apiVersion").orDash()} · protocol ${info.str("protocolVersion").orDash()}" +
                if (nodeVersion != null && nodeVersion != ctx.services.sdk.sdkNode) "   (jz built for ${ctx.services.sdk.sdkNode})" else "",
            "sync" to "${sync.str("syncStatus") ?: info.str("syncStatus").orDash()}   tip ${sync.long("tipHeight").orDash()} / target ${sync.long("targetHeight").orDash()}" +
                (sync.int("stagedBlocks")?.takeIf { it > 0 }?.let { "   staged $it" } ?: ""),
            "tip" to "#${height.orDash()}  ${tip.str("hash")?.short().orDash()}" + (tipTs?.let { "  ${relativeAge(it, Platform.nowMs())}" } ?: ""),
            "epoch" to "${epoch.long("index").orDash()}   slot ${epoch.long("currentSlot").orDash()} of ${epoch.long("epochHeight").orDash()}   next genesis #${nextGenesis.orDash()}" +
                (if (nextGenesis != null && height != null) "  (in ${humanDuration((nextGenesis - height) * slotMs / 1000)})" else ""),
            "validators" to "${(epoch["validators"] as? kotlinx.serialization.json.JsonArray)?.size.orDash()} active   slot owner ${epoch.str("currentSlotOwner")?.short().orDash()}",
            "peers" to (rpc.int("peers") ?: sync.int("peers")).orDash(),
            "finality" to (rpc.str("finality") ?: "-"),
            "node key" to info.str("nodeKey")?.short(14, 8).orDash(),
        ))
    }

    private fun Any?.orDash(): String = this?.toString() ?: "-"
}
