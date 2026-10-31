package org.janzeer.jz.cmd

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.janzeer.jz.ExitCodes
import org.janzeer.jz.JzContext
import org.janzeer.jz.JzException
import org.janzeer.jz.Prompts
import org.janzeer.jz.out.Render
import org.janzeer.jz.out.bool
import org.janzeer.jz.out.get
import org.janzeer.jz.out.humanDuration
import org.janzeer.jz.out.int
import org.janzeer.jz.out.long
import org.janzeer.jz.out.orDash
import org.janzeer.jz.out.str
import org.janzeer.jz.platform.Platform

/**
 * Operator view of ONE node, normally the local one (`--profile local` or `--node http://localhost:7019`). The update and
 * log commands also need the node's install directory: `--node-dir` > `JZ_NODE_DIR` > profile `node_dir` > the folder the
 * jz binary sits in when it is inside a node bundle (VERSION + update.sh next to it).
 */
class NodeCmd : CliktCommand(name = "node") {
    override fun help(context: Context) = "Look after a node: status, sync, version, health, update check/run, logs"
    init { subcommands(Status(), Sync(), Version(), Health(), Peers(), Update(), Logs()) }
    override fun run() = Unit

    companion object {
        fun bundleDir(ctx: JzContext, opt: String?): String? {
            val candidates = listOfNotNull(opt, Platform.env("JZ_NODE_DIR"), ctx.settings.profile.nodeDir,
                Platform.executablePath()?.substringBeforeLast('/', missingDelimiterValue = ""),
                if (!Platform.isWindows()) "/opt/janzeer/node" else null)
            return candidates.firstOrNull { it.isNotEmpty() && Platform.exists(Platform.joinPath(it, "VERSION")) && (Platform.exists(Platform.joinPath(it, "update.sh")) || Platform.exists(Platform.joinPath(it, "update.cmd"))) }
        }
        fun versionFile(dir: String): Map<String, String> = Platform.readText(Platform.joinPath(dir, "VERSION")).lineSequence()
            .mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it).trim() to l.substring(it + 1).trim() } }.toMap()
        fun health(ctx: JzContext) = ctx.chain.getAbsolute(ctx.settings.profile.node.trimEnd('/') + "/actuator/health")
    }

    class Status : CliktCommand(name = "status") {
        override fun help(context: Context) = "Everything at a glance: version, sync, tip, peers, health, update availability"
        private val ctx by requireObject<JzContext>()
        override fun run() {
            val info = ctx.chain.get("info"); ctx.checkNode(info)
            val sync = ctx.chain.get("info/sync"); val upd = ctx.chain.get("info/update"); val up = ctx.chain.get("info/uptime")
            val health = runCatching { health(ctx) }.getOrNull()
            val hz = health["components"]["janzeer"]["details"]
            ctx.out.data(buildJsonObject { put("info", info ?: JsonNull); put("sync", sync ?: JsonNull); put("update", upd ?: JsonNull); put("uptime", up ?: JsonNull); put("health", health ?: JsonNull) })
            val target = sync.long("targetHeight"); val tip = sync.long("tipHeight")
            val behind = if (target != null && tip != null && target > tip) "  (${target - tip} behind)" else ""
            ctx.out.kv(buildList {
                add("node" to ctx.settings.profile.node)
                add("version" to "${info.str("version").orDash()}  (protocol ${info.str("protocolVersion").orDash()})" + when (upd.bool("updateAvailable")) { true -> "   UPDATE AVAILABLE: ${upd.str("latestVersion")}"; false -> "   up to date"; null -> "" })
                add("network" to info.str("networkId").orDash())
                add("sync" to "${sync.str("syncStatus").orDash()}   tip ${tip.orDash()} / ${target.orDash()}$behind" + (sync.int("stagedBlocks")?.takeIf { it > 0 }?.let { "   staged $it" } ?: ""))
                add("peers" to (sync.int("peers") ?: hz.int("peers")).orDash())
                add("health" to (health.str("status") ?: "unreachable") + (health["components"]["db"].str("status")?.let { "   db $it" } ?: "") + (health["components"]["diskSpace"]["details"].long("free")?.let { "   disk free ${it / 1_000_000_000} GB" } ?: ""))
                up?.let { u -> (u.long("uptimeMs") ?: u.long("uptimeSeconds")?.times(1000) ?: u.long("uptime"))?.let { add("uptime" to humanDuration(it / 1000)) } ?: add("uptime" to Render.generic(u).joinToString("  ") { "${it.first} ${it.second}" }) }
                add("node key" to info.str("nodeKey").orDash())
            })
            if (health.str("status") != null && health.str("status") != "UP") throw JzException(ExitCodes.NODE_BUSY, "health", "health is ${health.str("status")}")
        }
        private fun Any?.orDash() = this?.toString() ?: "-"
    }

    class Sync : CliktCommand(name = "sync") {
        override fun help(context: Context) = "Sync progress (exit 4 while not SYNCHRONIZED, for scripts)"
        private val ctx by requireObject<JzContext>()
        override fun run() {
            val s = ctx.chain.get("info/sync")
            ctx.out.data(s ?: JsonNull)
            val tip = s.long("tipHeight") ?: 0; val target = s.long("targetHeight") ?: 0
            val pct = if (target > 0) tip * 100 / target else 0
            ctx.out.kv(listOf("status" to s.str("syncStatus").orDash(), "tip" to "$tip of $target  ($pct %)", "staged" to (s.int("stagedBlocks") ?: 0).toString(), "peers" to (s.int("peers") ?: 0).toString()))
            if (s.str("syncStatus") != "SYNCHRONIZED") throw JzException(ExitCodes.NODE_BUSY, "sync", "node is ${s.str("syncStatus") ?: "not synchronized"}")
        }
    }

    class Version : CliktCommand(name = "version") {
        override fun help(context: Context) = "Node version, protocol, and the newest published release the node knows of"
        private val ctx by requireObject<JzContext>()
        override fun run() {
            val info = ctx.chain.get("info"); val upd = ctx.chain.get("info/update")
            ctx.out.data(buildJsonObject { put("version", info.str("version")); put("apiVersion", info.str("apiVersion")); put("protocolVersion", info.str("protocolVersion")); put("update", upd ?: JsonNull) })
            ctx.out.kv(buildList {
                add("node" to info.str("version").orDash()); add("API" to info.str("apiVersion").orDash()); add("protocol" to info.str("protocolVersion").orDash())
                if (upd != null) { add("latest release" to upd.str("latestVersion").orDash() + (upd.str("checkedAt")?.let { "   (checked $it)" } ?: "")); add("update" to when (upd.bool("updateAvailable")) { true -> "AVAILABLE — jz node update"; false -> "none"; null -> "check disabled" }) }
            })
            if (upd.bool("updateAvailable") == true) throw JzException(ExitCodes.UPDATE_AVAILABLE, "update", "update available: ${upd.str("latestVersion")}", upd.str("url"))
        }
    }

    class Health : CliktCommand(name = "health") {
        override fun help(context: Context) = "Spring actuator health (exit 4 unless UP)"
        private val ctx by requireObject<JzContext>()
        override fun run() {
            val h = health(ctx) ?: throw JzException(ExitCodes.NETWORK, "health", "no /actuator/health on ${ctx.settings.profile.node}")
            ctx.out.data(h)
            ctx.out.kv(listOf("status" to h.str("status").orDash()) + ((h["components"] as? JsonObject)?.entries?.map { (k, v) -> k to (v.str("status").orDash() + (v["details"]?.let { d -> "  " + Render.generic(d).take(4).joinToString("  ") { "${it.first}=${it.second}" } } ?: "")) } ?: emptyList()))
            if (h.str("status") != "UP") throw JzException(ExitCodes.NODE_BUSY, "health", "health is ${h.str("status")}")
        }
    }

    class Peers : CliktCommand(name = "peers") {
        override fun help(context: Context) = "Connected peer count (the node exposes no peer list)"
        private val ctx by requireObject<JzContext>()
        override fun run() { ctx.out.scalar((ctx.chain.get("info/sync").int("peers") ?: ctx.chain.rpc("janzeer_getInfo").int("peers") ?: 0).toString()) }
    }

    class Update : CliktCommand(name = "update") {
        override fun help(context: Context) = "Check for a newer node release (exit 10 when one exists); --run executes the bundle's update.sh after confirmation"
        private val ctx by requireObject<JzContext>()
        private val nodeDir by option("--node-dir", metavar = "DIR", help = "The node bundle directory (VERSION + update.sh)")
        private val run by option("--run", help = "Run update.sh (downloads, verifies the minisign signature, swaps the binary, restarts the service)").flag()
        private val rollback by option("--rollback", help = "Run update.sh --rollback (restore the previous version)").flag()
        override fun run() {
            val dir = bundleDir(ctx, nodeDir)
            val installed = dir?.let { runCatching { versionFile(it) }.getOrNull() }
            val upd = runCatching { ctx.chain.get("info/update") }.getOrNull()
            val live = runCatching { ctx.chain.get("info") }.getOrNull()
            ctx.out.data(buildJsonObject { put("bundleDir", dir); put("installed", installed?.get("version")); put("running", live.str("version")); put("update", upd ?: JsonNull) })
            ctx.out.kv(buildList {
                add("bundle dir" to (dir ?: "not found — pass --node-dir or set node_dir in the profile"))
                installed?.let { add("installed" to "${it["version"].orDash()}  (${it["os"].orDash()}-${it["arch"].orDash()}, ${it["network"].orDash()})") }
                add("running" to live.str("version").orDash())
                upd?.let { add("latest" to it.str("latestVersion").orDash() + (it.str("checkedAt")?.let { c -> "   (node checked $c)" } ?: "")) }
            })
            if (dir == null) {
                if (run || rollback) throw JzException(ExitCodes.USAGE, "update", "cannot run update.sh without the bundle directory", "pass --node-dir /path/to/janzeer-node-… or set node_dir in jz.toml")
                if (upd.bool("updateAvailable") == true) throw JzException(ExitCodes.UPDATE_AVAILABLE, "update", "update available: ${upd.str("latestVersion")}", upd.str("url"))
                ctx.out.line(if (upd.bool("updateAvailable") == false) "up to date (per the node's own check)" else "no update information")
                return
            }
            if (Platform.isWindows()) {
                ctx.out.line("Windows: the bundle has no update script — download the new zip from the release page and replace janzeer.exe")
                if (upd.bool("updateAvailable") == true) throw JzException(ExitCodes.UPDATE_AVAILABLE, "update", "update available: ${upd.str("latestVersion")}", upd.str("url"))
                return
            }
            val script = Platform.joinPath(dir, "update.sh")
            if (rollback) {
                Prompts.typed(ctx, "rollback", "Restore the previous node version in $dir (restarts the service)")
                val rc = Platform.run(listOf("bash", script, "--rollback"), dir); if (rc != 0) throw JzException(ExitCodes.UNEXPECTED, "update", "update.sh --rollback exited $rc"); ctx.out.success("rolled back"); return
            }
            if (!run) {
                ctx.out.line(ctx.out.dim("running: bash update.sh --check")); ctx.out.line()
                when (val rc = Platform.run(listOf("bash", script, "--check"), dir)) {
                    0 -> ctx.out.success("up to date")
                    10 -> throw JzException(ExitCodes.UPDATE_AVAILABLE, "update", "update available", "jz node update --run installs it (downloads, verifies the signature, restarts the service)")
                    else -> throw JzException(ExitCodes.UNEXPECTED, "update", "update.sh --check exited $rc")
                }
                return
            }
            ctx.out.line("update.sh will: download the release for ${installed?.get("os")}-${installed?.get("arch")}, verify SHA256SUMS against the project's minisign key, keep the current files under .previous/, swap the binary and restart the service.")
            Prompts.typed(ctx, "update", "Update the node in $dir")
            val rc = Platform.run(listOf("bash", script), dir)
            if (rc != 0) throw JzException(ExitCodes.UNEXPECTED, "update", "update.sh exited $rc", "jz node update --rollback restores the previous version")
            ctx.out.success("updated — jz node status to verify")
        }
        private fun Any?.orDash() = this?.toString() ?: "-"
    }

    class Logs : CliktCommand(name = "logs") {
        override fun help(context: Context) = "Tail the node's log file (-f to follow). Looks in ~/logs/*/AppInfo.log, the bundle dir and run/logs; or give --file"
        private val ctx by requireObject<JzContext>()
        private val lines by option("-n", "--lines", help = "How many lines").int().default(200)
        private val follow by option("-f", "--follow", help = "Keep printing new lines (Ctrl-C to stop)").flag()
        private val file by option("--file", metavar = "PATH", help = "Explicit log file")
        private val nodeDir by option("--node-dir", metavar = "DIR")
        private val level by option("--level", help = "Which bundle log: info (default), warn or error").default("info")
        override fun run() {
            val path = file ?: discover() ?: throw JzException(ExitCodes.NOT_FOUND, "logs", "no node log file found",
                "under systemd use: journalctl -u janzeer-node -f   — or pass --file PATH")
            if (!Platform.exists(path)) throw JzException(ExitCodes.NOT_FOUND, "logs", "no such file: $path")
            ctx.out.data(JsonPrimitive(path))
            if (!ctx.out.json) { ctx.out.line(ctx.out.dim("== $path")); Platform.tail(path, lines).forEach { ctx.out.scalarRaw(it) } }
            if (!follow) return
            var pos = Platform.size(path)
            Platform.onShutdown { }
            while (true) {
                Platform.sleepMs(500)
                val now = Platform.size(path)
                if (now < pos) pos = 0 // rotated / truncated
                if (now > pos) { val chunk = Platform.readFrom(path, pos); pos = now; chunk.split('\n').dropLastWhile { it.isEmpty() }.forEach { ctx.out.scalarRaw(it) } }
            }
        }
        private fun discover(): String? {
            val name = when (level) { "warn" -> "AppWarn.log"; "error" -> "AppError.log"; else -> "AppInfo.log" }
            val candidates = buildList {
                bundleDir(ctx, nodeDir)?.let { d -> addAll(Platform.glob(Platform.joinPath(d, "logs", "*.log"))); addAll(Platform.glob(Platform.joinPath(d, "*.log"))) }
                addAll(Platform.glob(Platform.joinPath(Platform.homeDir(), "logs", "*", name)))
                addAll(Platform.glob(Platform.joinPath(Platform.homeDir(), "logs", "*.log")))
                addAll(Platform.glob(Platform.joinPath("run", "logs", "*.log")))
            }
            return candidates.maxByOrNull { Platform.modified(it) }
        }
    }
}
