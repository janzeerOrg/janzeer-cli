package org.janzeer.jz.cmd

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.janzeer.jz.ExitCodes
import org.janzeer.jz.JzContext
import org.janzeer.jz.JzException
import org.janzeer.jz.Prompts
import org.janzeer.jz.WalletFile
import org.janzeer.jz.platform.Platform

class WalletCmd : CliktCommand(name = "wallet") {
    override fun help(context: Context) = """
        Local wallets: an encrypted file per wallet under the config directory (PBKDF2 + AES-256-GCM, the same vault
        format as the Janzeer SDKs). Keys never leave this machine.
    """.trimIndent()
    init { subcommands(New(), Import(), ListCmd(), Show(), Export(), Remove()) }
    override fun run() = Unit

    class New : CliktCommand(name = "new") {
        override fun help(context: Context) = "Generate a fresh mnemonic and store it encrypted; the phrase is shown ONCE"
        private val ctx by requireObject<JzContext>()
        private val name by option("--name", help = "Wallet name (default: default_wallet from jz.toml, else 'main')")
        private val words by option("--words", help = "12 or 24 words").int().default(12)
        private val passphrase by option("--passphrase", help = "Also ask for a BIP39 passphrase (not stored; needed on every use)").flag()
        private val hidePhrase by option("--no-show", help = "Do not print the phrase (you must have another backup)").flag()

        override fun run() {
            val n = name ?: ctx.settings.defaultWallet
            if (ctx.wallets.exists(n)) throw JzException(ExitCodes.WALLET, "wallet", "wallet '$n' already exists", "pick another --name or jz wallet remove $n")
            val mnemonic = ctx.keys.generateMnemonic(words)
            val pp = if (passphrase) Platform.readPassword("BIP39 passphrase: ") ?: throw JzException(ExitCodes.WALLET, "passphrase", "no terminal for the passphrase") else ""
            val password = Prompts.password(ctx, "New password for wallet '$n'", confirm = true)
            val w = ctx.wallets.create(n, WalletFile.KIND_MNEMONIC, mnemonic, password, pp)
            ctx.out.data(buildJsonObject { put("name", w.name); put("address", w.address); put("file", w.path); if (!hidePhrase) put("mnemonic", mnemonic) })
            ctx.out.success("wallet '${w.name}' created")
            ctx.out.kv(listOf("address" to w.address, "file" to w.path))
            if (!hidePhrase) {
                ctx.out.line()
                ctx.out.line("Recovery phrase — write it down, it is shown only now and is NOT in the file:")
                ctx.out.line()
                ctx.out.line("    $mnemonic")
                ctx.out.line()
            }
        }
    }

    class Import : CliktCommand(name = "import") {
        override fun help(context: Context) = "Store an existing mnemonic, private key or SDK vault blob as a wallet"
        private val ctx by requireObject<JzContext>()
        private val name by option("--name", help = "Wallet name (default: default_wallet)")
        private val mnemonic by option("--mnemonic", metavar = "\"words\"", help = "BIP39 phrase (prompted without echo when none of the sources is given)")
        private val privateKey by option("--private-key", metavar = "HEX", help = "Raw secp256k1 private key")
        private val vaultFile by option("--vault-file", metavar = "FILE", help = "An SDK vault blob {v,salt,iv,ct} or a jz wallet file; its password is asked")
        private val passphrase by option("--passphrase", help = "Ask for a BIP39 passphrase (mnemonic wallets)").flag()

        override fun run() {
            val n = name ?: ctx.settings.defaultWallet
            if (ctx.wallets.exists(n)) throw JzException(ExitCodes.WALLET, "wallet", "wallet '$n' already exists")
            val pp = if (passphrase) Platform.readPassword("BIP39 passphrase: ") ?: "" else ""
            val (kind, secret) = when {
                vaultFile != null -> {
                    val f = vaultFile!!
                    if (!Platform.exists(f)) throw JzException(ExitCodes.WALLET, "wallet", "file not found: $f")
                    val parsed = ctx.wallets.parse(Platform.readText(f), f)
                    val pw = Prompts.password(ctx, "Password of $f")
                    val s = try { ctx.keys.decryptVault(parsed.vault, pw) } catch (e: Exception) { throw JzException(ExitCodes.WALLET, "vault", "cannot decrypt $f: wrong password or damaged blob", cause = e) }
                    val k = if (parsed.kind == WalletFile.KIND_PRIVATE_KEY || (!ctx.keys.validateMnemonic(s) && ctx.keys.isValidPrivateKey(s))) WalletFile.KIND_PRIVATE_KEY else WalletFile.KIND_MNEMONIC
                    k to s
                }
                privateKey != null -> WalletFile.KIND_PRIVATE_KEY to privateKey!!.removePrefix("0x")
                else -> {
                    val m = mnemonic ?: Platform.readPassword("Mnemonic: ") ?: throw JzException(ExitCodes.USAGE, "usage", "give --mnemonic, --private-key or --vault-file")
                    WalletFile.KIND_MNEMONIC to ctx.keys.normalizeMnemonic(m)
                }
            }
            val password = Prompts.password(ctx, "New password for wallet '$n'", confirm = true)
            val w = ctx.wallets.create(n, kind, secret, password, pp)
            ctx.out.data(buildJsonObject { put("name", w.name); put("address", w.address); put("kind", w.kind); put("file", w.path) })
            ctx.out.success("wallet '${w.name}' imported (${w.kind})")
            ctx.out.kv(listOf("address" to w.address, "file" to w.path))
        }
    }

    class ListCmd : CliktCommand(name = "list") {
        override fun help(context: Context) = "Wallets on this machine (no password needed)"
        private val ctx by requireObject<JzContext>()
        override fun run() {
            val all = ctx.wallets.list()
            val def = ctx.settings.defaultWallet
            ctx.out.data(buildJsonArray { all.forEach { w -> add(buildJsonObject { put("name", w.name); put("address", w.address); put("kind", w.kind); put("createdAt", w.createdAt); put("default", w.name == def) }) } })
            if (all.isEmpty()) { ctx.out.line("no wallets yet — jz wallet new"); return }
            ctx.out.table(listOf("name", "address", "kind", "created"), all.map { listOf(it.name + if (it.name == def) " *" else "", it.address, it.kind, it.createdAt.take(10)) })
        }
    }

    class Show : CliktCommand(name = "show") {
        override fun help(context: Context) = "Address and file of a wallet; with --verify it opens the vault and re-derives the address"
        private val ctx by requireObject<JzContext>()
        private val name by argument("NAME").optional()
        private val verify by option("--verify", help = "Open the vault (asks the password) and check the stored address").flag()
        override fun run() {
            val w = ctx.wallets.load(name ?: ctx.settings.defaultWallet)
            var derived: String? = null
            if (verify) {
                val pp = if (w.isMnemonic && Platform.stdinIsTty() && ctx.passwordFile == null) "" else ""
                derived = ctx.wallets.open(w, Prompts.password(ctx, "Password for wallet '${w.name}'"), pp).second.checksumAddress
                if (derived != w.address) ctx.out.warn("stored address ${w.address} differs from the derived $derived (passphrase wallet?)")
            }
            ctx.out.data(buildJsonObject { put("name", w.name); put("address", w.address); put("kind", w.kind); put("createdAt", w.createdAt); put("file", w.path); if (derived != null) put("derivedAddress", derived) })
            ctx.out.kv(buildList { add("name" to w.name); add("address" to w.address); add("kind" to w.kind); add("created" to w.createdAt); add("file" to w.path); if (derived != null) add("derived" to derived) })
        }
    }

    class Export : CliktCommand(name = "export") {
        override fun help(context: Context) = "Write the encrypted wallet file (or the bare SDK vault blob) elsewhere, or reveal the secret"
        private val ctx by requireObject<JzContext>()
        private val name by argument("NAME").optional()
        private val out by option("--out", metavar = "FILE", help = "Destination file (default: print to stdout)")
        private val blob by option("--blob", help = "Bare SDK vault blob {v,salt,iv,ct} instead of the jz wallet wrapper").flag()
        private val reveal by option("--reveal", help = "Decrypt and print the mnemonic / private key (asks for confirmation)").flag()
        private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

        override fun run() {
            val w = ctx.wallets.load(name ?: ctx.settings.defaultWallet)
            if (reveal) {
                val (secret, key) = ctx.wallets.open(w, Prompts.password(ctx, "Password for wallet '${w.name}'"))
                Prompts.typed(ctx, "reveal", "This prints the SECRET of wallet '${w.name}' (${w.kind})")
                ctx.out.data(buildJsonObject { put("name", w.name); put("kind", w.kind); put("address", key.checksumAddress); put("secret", secret) })
                ctx.out.kv(listOf("address" to key.checksumAddress, w.kind to secret))
                return
            }
            val text = if (blob) json.encodeToString(JsonObject.serializer(), w.vault) else ctx.wallets.render(w)
            if (out != null) {
                Platform.writeText(out!!, text, private = true)
                ctx.out.data(buildJsonObject { put("file", out) })
                ctx.out.success("wrote ${out} (encrypted; the password stays the same)")
            } else {
                // The file itself is the output in both modes.
                ctx.out.scalarRaw(text)
            }
        }
    }

    class Remove : CliktCommand(name = "remove") {
        override fun help(context: Context) = "Delete a wallet file (asks for confirmation; the phrase is your only backup)"
        private val ctx by requireObject<JzContext>()
        private val name by argument("NAME")
        override fun run() {
            val w = ctx.wallets.load(name)
            Prompts.typed(ctx, w.name, "Deleting wallet '${w.name}' (${w.address}) — not reversible")
            ctx.wallets.remove(w.name)
            ctx.out.data(buildJsonObject { put("removed", w.name) })
            ctx.out.success("removed ${w.path}")
        }
    }
}
