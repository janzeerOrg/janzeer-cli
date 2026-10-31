package org.janzeer.jz

import com.github.ajalt.clikt.completion.CompletionCommand
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.counted
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import org.janzeer.jz.api.ChainApi
import org.janzeer.jz.api.KeyOps
import org.janzeer.jz.api.Services
import org.janzeer.jz.cmd.AddressCmd
import org.janzeer.jz.cmd.BalanceCmd
import org.janzeer.jz.cmd.BlockCmd
import org.janzeer.jz.cmd.ConfigCmd
import org.janzeer.jz.cmd.InfoCmd
import org.janzeer.jz.cmd.KeyCmd
import org.janzeer.jz.cmd.NodeCmd
import org.janzeer.jz.cmd.NonceCmd
import org.janzeer.jz.cmd.SendCmd
import org.janzeer.jz.cmd.TokenCmd
import org.janzeer.jz.cmd.ValidatorCmd
import org.janzeer.jz.cmd.VectorsCmd
import org.janzeer.jz.cmd.TokensCmd
import org.janzeer.jz.cmd.TxCmd
import org.janzeer.jz.cmd.ValidatorsCmd
import org.janzeer.jz.cmd.VersionCmd
import org.janzeer.jz.cmd.WalletCmd
import org.janzeer.jz.cmd.WatchCmd
import org.janzeer.jz.out.str
import org.janzeer.jz.platform.Platform
import org.janzeer.jz.config.Profiles
import org.janzeer.jz.config.Settings
import org.janzeer.jz.out.Output

/** Shared state for one invocation, available to every subcommand through `requireObject<JzContext>()`. */
class JzContext(
    val out: Output,
    val services: Services,
    private val nodeOpt: String?,
    private val profileOpt: String?,
    val timeoutSec: Int,
    val yes: Boolean,
    val passwordFile: String?,
) {
    /** Resolved lazily: commands that never touch a node (`version`, `config path`) must not fail on a bad profile. */
    val settings: Settings by lazy { Profiles.resolve(nodeOpt, profileOpt) }
    /** The node connection for this run; opened on first use, closed by [close]. */
    val chain: ChainApi by lazy { services.chain(settings.profile, timeoutSec).also { opened = it } }
    private var opened: ChainApi? = null
    val wallets: WalletStore by lazy { WalletStore(Platform.walletsDir(), services.keys) }
    val keys: KeyOps get() = services.keys
    fun close() { opened?.close() }

    /** Warn once when the node's network id or version is not what this jz was built for. */
    fun checkNode(info: kotlinx.serialization.json.JsonElement?) {
        val net = info.str("networkId")
        val expected = settings.profile.networkId
        if (net != null && expected != null && net != expected)
            throw JzException(ExitCodes.NETWORK_MISMATCH, "network-mismatch", "node is on network '$net' but profile '${settings.profile.name}' expects '$expected'", "use --node URL for an ad-hoc node or fix jz.toml")
        val v = info.str("version")
        if (v != null && v != services.sdk.sdkNode) out.debug("node ${v}, jz built for ${services.sdk.sdkNode}")
    }
}

/** Root command. Subcommands are added by [build]; the list grows with the milestones. */
class Jz(private val services: Services, private val stdout: (String) -> Unit, private val stderr: (String) -> Unit, private val tty: Boolean) :
    CliktCommand(name = BuildInfo.NAME) {

    override fun help(context: Context): String = """
        Command-line client for the Janzeer blockchain: read the chain, watch it live, keep a local wallet, send
        transactions and look after a node. Keys never leave this machine; the node only verifies.

        Node selection: --node URL > JZ_NODE > --profile NAME > JZ_PROFILE > default_profile in jz.toml > mainnet.
    """.trimIndent()

    override fun helpEpilog(context: Context): String = "Docs: ${BuildInfo.HOMEPAGE}/docs   Source: ${BuildInfo.REPO}"

    private val node by option("--node", metavar = "URL|PROFILE", envvar = "JZ_NODE", help = "Node base URL (REST at /api/v1) or a profile name")
    private val profile by option("--profile", metavar = "NAME", envvar = "JZ_PROFILE", help = "Profile from jz.toml (mainnet, testnet, local, …)")
    private val json by option("--json", help = "Print the raw JSON instead of tables").flag()
    private val quiet by option("-q", "--quiet", help = "No informational output; errors still go to stderr").flag()
    private val noColor by option("--no-color", envvar = "NO_COLOR", help = "Disable colours").flag()
    private val timeout by option("--timeout", metavar = "SEC", help = "HTTP call timeout").int().default(15)
    private val verbose by option("-v", "--verbose", help = "Debug output on stderr").counted()
    private val yes by option("-y", "--yes", help = "Answer yes to confirmations (writes, reveals)").flag()
    private val passwordFile by option("--password-file", metavar = "FILE", envvar = "JZ_PASSWORD_FILE", help = "Read the wallet password from this file (first line) instead of prompting")

    override fun run() {
        currentContext.data[Context.DEFAULT_OBJ_KEY] = JzContext(Output(json, quiet, verbose > 0, noColor, tty, stdout, stderr), services, node, profile, timeout, yes, passwordFile)
    }

    companion object {
        private val globalFlags = setOf("--json", "-q", "--quiet", "--no-color", "-v", "--verbose", "-y", "--yes")
        private val globalValued = setOf("--node", "--profile", "--timeout", "--password-file")

        /**
         * Global options may be written anywhere (`jz info --json` reads as naturally as `jz --json info`), but Clikt
         * parses them only before the subcommand. Move them to the front; everything after `--` is left alone.
         */
        fun hoistGlobals(args: List<String>): List<String> {
            val globals = ArrayList<String>()
            val rest = ArrayList<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "--" -> { rest.addAll(args.subList(i, args.size)); break }
                    a in globalFlags -> globals.add(a)
                    a in globalValued && i + 1 < args.size -> { globals.add(a); globals.add(args[i + 1]); i++ }
                    globalValued.any { a.startsWith("$it=") } -> globals.add(a)
                    else -> rest.add(a)
                }
                i++
            }
            return globals + rest
        }

        /** The full command tree. */
        fun build(services: Services, stdout: (String) -> Unit, stderr: (String) -> Unit, tty: Boolean): Jz =
            Jz(services, stdout, stderr, tty).subcommands(
                InfoCmd(),
                BlockCmd(), TxCmd(), AddressCmd(), BalanceCmd(), NonceCmd(), ValidatorsCmd(), TokensCmd(),
                WatchCmd(),
                WalletCmd(),
                SendCmd(), TokenCmd(), ValidatorCmd(),
                NodeCmd(),
                VectorsCmd(),
                KeyCmd(),
                VersionCmd(),
                ConfigCmd(),
                CompletionCommand(name = "completion", help = "Shell completion script: eval \"\$(jz completion bash)\""),
            )
    }
}
