package org.janzeer.jz.config

import org.janzeer.jz.ExitCodes
import org.janzeer.jz.JzException
import org.janzeer.jz.platform.Platform

/** One node to talk to. [node] is the base URL; REST is `<node>/api/v1`, RPC `<node>/rpc`, WS `<node>/rpc/ws` unless overridden. */
data class Profile(
    val name: String,
    val node: String,
    val networkId: String?,
    val rpc: String? = null,
    val ws: String? = null,
    val nodeDir: String? = null,
)

/** The resolved settings for one invocation. */
data class Settings(
    val profile: Profile,
    val defaultWallet: String,
    val configPath: String,
    val configExists: Boolean,
    /** Where the profile came from: `--node`, `JZ_NODE`, `--profile`, `JZ_PROFILE`, `default_profile`, `built-in`. */
    val source: String,
)

object Profiles {
    val builtIn: List<Profile> = listOf(
        Profile("mainnet", "https://explorer.janzeer.org", "janzeer"),
        Profile("testnet", "https://testnet.janzeer.org", "janzeer-testnet"),
        Profile("local", "http://localhost:7019", "janzeer"),
    )

    fun configPath(): String = Platform.joinPath(Platform.configDir(), "jz.toml")

    /** Parsed `jz.toml` (empty when absent). */
    fun load(path: String = configPath()): Map<String, Any> =
        if (Platform.exists(path)) Toml.parse(Platform.readText(path)) else emptyMap()

    /** Built-in profiles overlaid by the file's `[profiles.<name>]` tables. */
    fun all(cfg: Map<String, Any>): Map<String, Profile> {
        val out = LinkedHashMap<String, Profile>()
        builtIn.forEach { out[it.name] = it }
        val names = cfg.keys.filter { it.startsWith("profiles.") }.map { it.removePrefix("profiles.").substringBefore('.') }.distinct()
        for (n in names) {
            val base = out[n]
            fun s(k: String): String? = cfg["profiles.$n.$k"]?.toString()
            val node = s("node") ?: base?.node ?: throw JzException(ExitCodes.USAGE, "config", "profile '$n' has no node = \"…\"")
            out[n] = Profile(n, node, s("network_id") ?: base?.networkId, s("rpc") ?: base?.rpc, s("ws") ?: base?.ws, s("node_dir") ?: base?.nodeDir)
        }
        return out
    }

    /**
     * Precedence: `--node` > `JZ_NODE` > `--profile` > `JZ_PROFILE` > `default_profile` in the file > built-in mainnet.
     * A URL given directly becomes an ad-hoc profile named `custom` (no network id → nothing to check against).
     */
    fun resolve(nodeOpt: String?, profileOpt: String?, configPath: String = configPath()): Settings {
        val exists = Platform.exists(configPath)
        val cfg = load(configPath)
        val profiles = all(cfg)
        val defaultWallet = cfg["default_wallet"]?.toString() ?: "main"
        fun byName(n: String, source: String): Settings {
            val p = profiles[n] ?: throw JzException(ExitCodes.USAGE, "config",
                "unknown profile '$n'", "known: ${profiles.keys.joinToString(", ")} — add [profiles.$n] to $configPath")
            return Settings(p, defaultWallet, configPath, exists, source)
        }
        val envNode = Platform.env("JZ_NODE")?.takeIf { it.isNotBlank() }
        val envProfile = Platform.env("JZ_PROFILE")?.takeIf { it.isNotBlank() }
        return when {
            nodeOpt != null -> Settings(adHoc(nodeOpt, profiles), defaultWallet, configPath, exists, "--node")
            envNode != null -> Settings(adHoc(envNode, profiles), defaultWallet, configPath, exists, "JZ_NODE")
            profileOpt != null -> byName(profileOpt, "--profile")
            envProfile != null -> byName(envProfile, "JZ_PROFILE")
            cfg["default_profile"] != null -> byName(cfg["default_profile"].toString(), "default_profile")
            else -> byName("mainnet", "built-in")
        }
    }

    /** `--node local` or `JZ_NODE=testnet` may name a profile; anything else is a URL. */
    private fun adHoc(value: String, profiles: Map<String, Profile>): Profile {
        profiles[value]?.let { return it }
        val url = value.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://"))
            throw JzException(ExitCodes.USAGE, "config", "node must be an http(s) URL or a profile name, got '$value'")
        return Profile("custom", url, null)
    }

    /** The file `config init` writes. */
    fun defaultConfig(): String = Toml.render(
        linkedMapOf<String, Any>(
            "default_profile" to "mainnet",
            "default_wallet" to "main",
            "profiles.mainnet.node" to "https://explorer.janzeer.org",
            "profiles.mainnet.network_id" to "janzeer",
            "profiles.testnet.node" to "https://testnet.janzeer.org",
            "profiles.testnet.network_id" to "janzeer-testnet",
            "profiles.local.node" to "http://localhost:7019",
            "profiles.local.network_id" to "janzeer",
        ),
        mapOf(
            "profiles.mainnet.node" to "REST <node>/api/v1, JSON-RPC <node>/rpc, WebSocket <node>/rpc/ws; optional rpc = / ws = overrides",
            "profiles.local.network_id" to "add node_dir = \"/opt/janzeer\" for jz node update / logs",
        ),
    )
}
