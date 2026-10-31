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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.janzeer.jz.ExitCodes
import org.janzeer.jz.JzContext
import org.janzeer.jz.JzException
import org.janzeer.jz.Prompts
import org.janzeer.jz.platform.Platform

class KeyCmd : CliktCommand(name = "key") {
    override fun help(context: Context) = "Key tools: derive from a mnemonic, sign and verify a hash, inspect an address"
    init { subcommands(Derive(), Sign(), Verify(), AddressCmd()) }
    override fun run() = Unit

    class Derive : CliktCommand(name = "derive") {
        override fun help(context: Context) = "Address and keys from a BIP39 phrase (Janzeer derivation, path m/0/0/0). Nothing touches the network."
        private val ctx by requireObject<JzContext>()
        private val mnemonic by option("--mnemonic", metavar = "\"12 or 24 words\"", help = "The phrase; prompted without echo when omitted")
        private val passphrase by option("--passphrase", help = "Optional BIP39 passphrase (default: none)").default("")
        private val reveal by option("--reveal", help = "Also print the private key (asks for confirmation)").flag()

        override fun run() {
            val m = mnemonic ?: Platform.readPassword("Mnemonic: ") ?: throw JzException(ExitCodes.USAGE, "usage", "pass --mnemonic or run in a terminal")
            val k = ctx.keys.fromMnemonic(ctx.keys.normalizeMnemonic(m), passphrase)
            if (reveal) Prompts.typed(ctx, "reveal", "This prints the PRIVATE KEY")
            ctx.out.data(buildJsonObject {
                put("address", k.checksumAddress); put("publicKey", k.publicKeyHex); put("path", "m/0/0/0")
                if (reveal) put("privateKey", k.privateKeyHex)
            })
            ctx.out.kv(buildList {
                add("address" to k.checksumAddress); add("public key" to k.publicKeyHex); add("path" to "m/0/0/0")
                if (reveal) add("private key" to k.privateKeyHex)
            })
        }
    }

    class Sign : CliktCommand(name = "sign") {
        override fun help(context: Context) = "Sign a 32-byte hash (hex) with a wallet key: RFC-6979 low-S DER, Base64 — the node's signature format"
        private val ctx by requireObject<JzContext>()
        private val hash by option("--hash", metavar = "HEX", help = "32-byte digest to sign").required()
        private val wallet by option("--wallet", metavar = "NAME", help = "Wallet whose key signs (default: default_wallet)")
        private val privateKeyStdin by option("--private-key-stdin", help = "Read a raw private key (hex) from stdin instead of a wallet").flag()

        override fun run() {
            val h = hash.removePrefix("0x")
            if (h.length != 64 || !h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) throw JzException(ExitCodes.USAGE, "usage", "--hash must be 64 hex characters")
            val priv = if (privateKeyStdin) (readlnOrNull()?.trim() ?: throw JzException(ExitCodes.USAGE, "usage", "no private key on stdin"))
            else {
                val w = ctx.wallets.load(wallet ?: ctx.settings.defaultWallet)
                ctx.wallets.open(w, Prompts.password(ctx, "Password for wallet '${w.name}'")).second.privateKeyHex
            }
            val sig = ctx.keys.signHash(h, priv)
            ctx.out.scalar(sig)
        }
    }

    class Verify : CliktCommand(name = "verify") {
        override fun help(context: Context) = "Verify a Base64 DER signature over a 32-byte hash against a compressed public key"
        private val ctx by requireObject<JzContext>()
        private val hash by option("--hash", metavar = "HEX").required()
        private val signature by option("--signature", metavar = "BASE64").required()
        private val publicKey by option("--public-key", metavar = "HEX").required()
        override fun run() {
            val ok = ctx.keys.verifyHash(hash, signature, publicKey)
            ctx.out.data(JsonPrimitive(ok))
            ctx.out.line(if (ok) "valid" else "INVALID")
            if (!ok) throw JzException(ExitCodes.TX_REJECTED, "signature", "signature does not verify")
        }
    }

    class AddressCmd : CliktCommand(name = "address") {
        override fun help(context: Context) = "Checksum form and validity of an address, or the address of a compressed public key"
        private val ctx by requireObject<JzContext>()
        private val value by argument("PUBKEY|ADDRESS")
        override fun run() {
            val v = value.trim()
            val address = when {
                v.removePrefix("0x").length == 66 -> ctx.keys.fromPrivateKeyOrPublic(v)
                else -> v
            }
            if (!ctx.keys.isValidAddress(address)) throw JzException(ExitCodes.USAGE, "address", "not a valid address: $value")
            val checksum = ctx.keys.checksumAddress(address)
            ctx.out.data(buildJsonObject { put("address", checksum); put("valid", true); put("checksumMatches", address == checksum || address.lowercase() == address) })
            ctx.out.kv(listOf("address" to checksum, "input" to (if (address == checksum) "checksum form" else if (address.lowercase() == address) "lower-case form" else "MIXED CASE WITH WRONG CHECKSUM")))
        }
    }
}

/** A 33-byte compressed public key → address, via the SDK's Address.fromPublicKey (implemented in the adapter as a KeyOps extension point). */
private fun org.janzeer.jz.api.KeyOps.fromPrivateKeyOrPublic(hex: String): String = addressOfPublicKey(hex)
