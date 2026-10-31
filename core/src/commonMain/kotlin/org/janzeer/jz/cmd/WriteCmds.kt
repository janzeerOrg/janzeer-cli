package org.janzeer.jz.cmd

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.janzeer.jz.ExitCodes
import org.janzeer.jz.JzContext
import org.janzeer.jz.JzException
import org.janzeer.jz.TxFlow
import org.janzeer.jz.out.str

/** Options every write shares. */
abstract class WriteCmd(name: String) : CliktCommand(name = name) {
    protected val ctx by requireObject<JzContext>()
    private val wallet by option("--wallet", metavar = "NAME", help = "Wallet that signs (default: default_wallet)")
    private val fee by option("--fee", metavar = "JNZ", help = "Fee to pay; the node's minimum is used when lower, a fixed fee cannot be changed")
    private val nonce by option("--nonce", metavar = "N", help = "Explicit nonce (default: the node's next nonce for the sender)").long()
    private val wait by option("--wait", help = "Wait until the transaction is final").flag()
    private val waitTimeout by option("--wait-timeout", metavar = "SEC").int().default(180)
    private val dryRun by option("--dry-run", help = "Build and sign, print the request body, send nothing").flag()
    protected fun options() = TxFlow.Options(wallet, fee, nonce, wait, waitTimeout, dryRun)

    protected fun address(a: String, what: String): String {
        if (!ctx.keys.isValidAddress(a)) throw JzException(ExitCodes.USAGE, "usage", "$what is not a valid address: $a")
        return a.lowercase()
    }
    protected fun amount(a: String, what: String): String {
        if (!ctx.keys.isValidAmount(a)) throw JzException(ExitCodes.USAGE, "usage", "$what must be a decimal amount (up to 8 decimals), got '$a'")
        return ctx.keys.normalizeAmount(a)
    }
    protected fun key(k: String): String {
        val h = k.removePrefix("0x")
        if (h.length != 66 || !h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } || h[0] != '0' || h[1] !in "23") throw JzException(ExitCodes.USAGE, "usage", "--key must be a 33-byte compressed public key (66 hex chars starting 02 or 03)")
        return h.lowercase()
    }
}

class SendCmd : WriteCmd("send") {
    override fun help(context: Context) = "Transfer JNZ: jz send 0xRECIPIENT 12.5 [--memo text]"
    private val to by argument("TO")
    private val amount by argument("AMOUNT")
    private val memo by option("--memo", metavar = "TEXT", help = "Optional data field (up to 256 bytes)")
    override fun run() {
        val t = address(to, "TO"); val a = amount(amount, "AMOUNT")
        TxFlow.run(ctx, TxFlow.Request("transfer", "transfer",
            buildJsonObject { put("to", t); put("amount", a); if (memo != null) put("data", memo) },
            buildList { add("to" to ctx.keys.checksumAddress(t)); add("amount" to ctx.keys.formatJnz(a)); if (memo != null) add("memo" to memo!!) }), options())
    }
}

class ValidatorCmd : CliktCommand(name = "validator") {
    override fun help(context: Context) = "Register or exit a validator (the node key from its config.json; the deposit is NOT refundable)"
    init { subcommands(Register(), Exit()) }
    override fun run() = Unit

    class Register : WriteCmd("register") {
        override fun help(context: Context) = "Register a validator: pays the deposit from the wallet to the treasury; the key joins the rotation at the next epoch"
        private val key by option("--key", metavar = "NODE_KEY", help = "The node's public key (nodeKey in GET /api/v1/info of that node)").required()
        override fun run() {
            val k = key(key)
            val spec = ctx.chain.rpc("janzeer_getChainSpec")
            val deposit = spec.str("validatorDeposit") ?: "2000"
            TxFlow.run(ctx, TxFlow.Request("registerValidator", "registerValidator",
                buildJsonObject { put("validatorKey", k); put("amount", deposit) },
                listOf("validator key" to k, "deposit" to ctx.keys.formatJnz(deposit)),
                warning = "The deposit of ${ctx.keys.formatJnz(deposit)} goes to the treasury and is NEVER refunded, not even on exit.",
                typedWord = "register"), options())
        }
    }

    class Exit : WriteCmd("exit") {
        override fun help(context: Context) = "Retire a validator: it leaves the schedule at the next epoch boundary; the deposit stays gone"
        private val key by option("--key", metavar = "NODE_KEY").required()
        override fun run() {
            val k = key(key)
            TxFlow.run(ctx, TxFlow.Request("exitValidator", "exitValidator", buildJsonObject { put("validatorKey", k) }, listOf("validator key" to k),
                warning = "Exiting is final for this key; registering again costs a new deposit.", typedWord = "exit"), options())
        }
    }
}

class TokenCmd : CliktCommand(name = "token") {
    override fun help(context: Context) = "JZT-1 token operations: create, mint, burn, setcap, transfer"
    init { subcommands(Create(), Mint(), Burn(), SetCap(), Transfer()) }
    override fun run() = Unit

    private fun WriteCmd.tokenAmount(a: String, what: String): String {
        if (a.isEmpty() || !a.all { it.isDigit() }) throw JzException(ExitCodes.USAGE, "usage", "$what must be a whole number of base units (apply the token's decimals yourself)")
        return a.trimStart('0').ifEmpty { "0" }
    }

    class Create : WriteCmd("create") {
        override fun help(context: Context) = "Create a token (fixed creation fee): --symbol JZX --name \"My token\" [--decimals 2] [--cap N] [--mint N]"
        private val symbol by option("--symbol", help = "1–8 upper-case letters/digits").required()
        private val name by option("--name", help = "Display name").required()
        private val decimals by option("--decimals").int().default(0)
        private val cap by option("--cap", metavar = "UNITS", help = "Maximum supply in base units (optional)")
        private val mint by option("--mint", metavar = "UNITS", help = "Initial supply minted to the creator (optional)")
        override fun run() {
            if (decimals !in 0..18) throw JzException(ExitCodes.USAGE, "usage", "--decimals must be 0..18")
            TxFlow.run(ctx, TxFlow.Request("token", "tokenCreate",
                buildJsonObject { put("op", "create"); put("symbol", symbol); put("name", name); put("decimals", decimals); if (cap != null) put("cap", cap); if (mint != null) put("amount", mint) },
                buildList { add("create token" to "$symbol — $name"); add("decimals" to "$decimals"); if (cap != null) add("cap" to cap!!); if (mint != null) add("initial mint" to mint!!) }), options())
        }
    }
    class Mint : WriteCmd("mint") {
        override fun help(context: Context) = "Mint base units of your token: jz token mint TOKEN_ID UNITS [--to ADDRESS]"
        private val tokenId by argument("TOKEN_ID"); private val units by argument("UNITS")
        private val to by option("--to", metavar = "ADDRESS", help = "Recipient (default: the minter)")
        override fun run() {
            val r = to?.let { address(it, "--to") }
            TxFlow.run(ctx, TxFlow.Request("token", "token", buildJsonObject { put("op", "mint"); put("tokenId", tokenId); put("amount", units); if (r != null) put("recipient", r) },
                buildList { add("mint" to "$units units of $tokenId"); if (r != null) add("to" to ctx.keys.checksumAddress(r)) }), options())
        }
    }
    class Burn : WriteCmd("burn") {
        override fun help(context: Context) = "Burn base units you hold"
        private val tokenId by argument("TOKEN_ID"); private val units by argument("UNITS")
        override fun run() = TxFlow.run(ctx, TxFlow.Request("token", "token", buildJsonObject { put("op", "burn"); put("tokenId", tokenId); put("amount", units) }, listOf("burn" to "$units units of $tokenId")), options())
    }
    class SetCap : WriteCmd("setcap") {
        override fun help(context: Context) = "Set the maximum supply (owner only)"
        private val tokenId by argument("TOKEN_ID"); private val cap by argument("CAP_UNITS")
        override fun run() = TxFlow.run(ctx, TxFlow.Request("token", "token", buildJsonObject { put("op", "setcap"); put("tokenId", tokenId); put("cap", cap) }, listOf("set cap" to "$cap units for $tokenId")), options())
    }
    class Transfer : WriteCmd("transfer") {
        override fun help(context: Context) = "Send base units of a token: jz token transfer TOKEN_ID UNITS 0xRECIPIENT"
        private val tokenId by argument("TOKEN_ID"); private val units by argument("UNITS"); private val to by argument("TO")
        override fun run() {
            val r = address(to, "TO")
            TxFlow.run(ctx, TxFlow.Request("token", "token", buildJsonObject { put("op", "transfer"); put("tokenId", tokenId); put("amount", units); put("recipient", r) },
                listOf("transfer" to "$units units of $tokenId", "to" to ctx.keys.checksumAddress(r))), options())
        }
    }
}
