package org.janzeer.jz.cmd

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.janzeer.jz.ExitCodes
import org.janzeer.jz.JzContext
import org.janzeer.jz.JzException
import org.janzeer.jz.config.Profiles
import org.janzeer.jz.platform.Platform

class ConfigCmd : CliktCommand(name = "config") {
    override fun help(context: Context) = "Show, locate or create jz.toml (profiles and defaults)"
    init { subcommands(Show(), Path(), Init()) }
    override fun run() = Unit

    class Show : CliktCommand(name = "show") {
        override fun help(context: Context) = "Effective settings: active profile, where it came from, all known profiles"
        private val ctx by requireObject<JzContext>()
        override fun run() {
            val s = ctx.settings
            val all = Profiles.all(Profiles.load(s.configPath))
            ctx.out.data(buildJsonObject {
                put("config", s.configPath); put("configExists", s.configExists); put("source", s.source)
                put("profile", buildJsonObject { put("name", s.profile.name); put("node", s.profile.node); put("networkId", s.profile.networkId); put("nodeDir", s.profile.nodeDir) })
                put("defaultWallet", s.defaultWallet)
                put("profiles", buildJsonObject { all.values.forEach { p -> put(p.name, buildJsonObject { put("node", p.node); put("networkId", p.networkId) }) } })
            })
            ctx.out.kv(listOf(
                "config file" to (s.configPath + if (s.configExists) "" else "  (not present — jz config init)"),
                "active profile" to "${s.profile.name}  (from ${s.source})",
                "node" to s.profile.node,
                "network id" to (s.profile.networkId ?: "unknown — not checked"),
                "node dir" to (s.profile.nodeDir ?: "-"),
                "default wallet" to s.defaultWallet,
            ))
            ctx.out.line()
            ctx.out.table(listOf("profile", "node", "network id"), all.values.map { listOf(it.name, it.node, it.networkId ?: "-") })
        }
    }

    class Path : CliktCommand(name = "path") {
        override fun help(context: Context) = "Print the config file path"
        private val ctx by requireObject<JzContext>()
        override fun run() { ctx.out.scalar(Profiles.configPath()) }
    }

    class Init : CliktCommand(name = "init") {
        override fun help(context: Context) = "Write a jz.toml with the built-in profiles (mainnet, testnet, local)"
        private val ctx by requireObject<JzContext>()
        private val force by option("--force", help = "Overwrite an existing file").flag()
        override fun run() {
            val path = Profiles.configPath()
            if (Platform.exists(path) && !force) throw JzException(ExitCodes.USAGE, "config", "$path exists", "use --force to overwrite it")
            Platform.writeText(path, Profiles.defaultConfig())
            ctx.out.data(JsonPrimitive(path))
            ctx.out.success("wrote $path")
        }
    }
}
