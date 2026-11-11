package org.janzeer.jz

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.janzeer.jz.api.KeyInfo
import org.janzeer.jz.api.KeyOps
import org.janzeer.jz.platform.Platform

/** One wallet file: `~/.config/janzeer/wallets/<name>.json`. The secret is inside [vault]; [address] is in clear. */
data class WalletFile(val name: String, val kind: String, val address: String, val createdAt: String, val vault: JsonObject, val path: String) {
    val isMnemonic: Boolean get() = kind == KIND_MNEMONIC
    companion object {
        const val FORMAT = "janzeer-wallet"
        const val VERSION = 1
        const val KIND_MNEMONIC = "mnemonic"
        const val KIND_PRIVATE_KEY = "privateKey"
    }
}

/** Reads and writes wallet files; never prints anything. */
class WalletStore(private val dir: String, private val keys: KeyOps) {
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun path(name: String): String = Platform.joinPath(dir, "$name.json")

    fun list(): List<WalletFile> = Platform.listFiles(dir).filter { it.endsWith(".json") }.mapNotNull { p -> runCatching { parse(Platform.readText(p), p) }.getOrNull() }

    fun exists(name: String): Boolean = Platform.exists(path(name))

    fun load(name: String): WalletFile {
        val p = path(name)
        if (!Platform.exists(p)) throw JzException(ExitCodes.WALLET, "wallet", "no wallet named '$name'", "jz wallet list · jz wallet new --name $name · jz wallet import --name $name")
        return parse(Platform.readText(p), p)
    }

    /** Validates the name (file-system safe), refuses to overwrite. */
    fun create(name: String, kind: String, secret: String, password: String, passphrase: String): WalletFile {
        checkName(name)
        if (exists(name)) throw JzException(ExitCodes.WALLET, "wallet", "wallet '$name' already exists", "jz wallet remove $name first, or pick another --name")
        val key: KeyInfo = if (kind == WalletFile.KIND_MNEMONIC) keys.fromMnemonic(secret, passphrase) else keys.fromPrivateKey(secret)
        val file = WalletFile(name, kind, key.checksumAddress, Platform.nowIso(), keys.encryptVault(secret, password), path(name))
        Platform.writeText(file.path, render(file), private = true)
        return file
    }

    /** Opens the vault; a wrong password is reported as exit 8. */
    fun open(file: WalletFile, password: String, passphrase: String = ""): Pair<String, KeyInfo> {
        val secret = try { keys.decryptVault(file.vault, password) } catch (e: Exception) {
            throw JzException(ExitCodes.WALLET, "wallet", "cannot open wallet '${file.name}': wrong password or damaged vault", cause = e)
        }
        val key = if (file.isMnemonic) keys.fromMnemonic(secret, passphrase) else keys.fromPrivateKey(secret)
        return secret to key
    }

    /** Deletes the file the wallet was LOADED from. The name stored inside a wallet file is not the file's name once the
     *  file was renamed by hand; deleting by that inner name removed a different wallet (review 2026-11-11). */
    fun remove(file: WalletFile) { Platform.delete(file.path) }

    fun render(w: WalletFile): String = json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("format", WalletFile.FORMAT); put("v", WalletFile.VERSION); put("name", w.name); put("kind", w.kind)
        put("address", w.address); put("createdAt", w.createdAt); put("vault", w.vault)
    })

    fun parse(text: String, path: String): WalletFile {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw JzException(ExitCodes.WALLET, "wallet", "$path is not a wallet file") }
        // A bare SDK vault blob {v,salt,iv,ct} is accepted too (import --vault-file); name/address come from the caller.
        if (o["format"]?.jsonPrimitive?.content != WalletFile.FORMAT) {
            if (o["ct"] != null && o["salt"] != null && o["iv"] != null) return WalletFile("", WalletFile.KIND_MNEMONIC, "", "", o, path)
            throw JzException(ExitCodes.WALLET, "wallet", "$path is not a janzeer-wallet file")
        }
        fun s(k: String) = o[k]?.jsonPrimitive?.content ?: throw JzException(ExitCodes.WALLET, "wallet", "$path: missing '$k'")
        return WalletFile(s("name"), s("kind"), s("address"), s("createdAt"), o["vault"]?.jsonObject ?: throw JzException(ExitCodes.WALLET, "wallet", "$path: missing vault"), path)
    }

    private fun checkName(name: String) {
        if (name.isEmpty() || name.length > 40 || !name.all { it.isLetterOrDigit() || it == '-' || it == '_' })
            throw JzException(ExitCodes.USAGE, "wallet", "wallet name must be 1–40 letters, digits, '-' or '_'")
    }

    companion object {
        fun primitive(v: String): JsonPrimitive = JsonPrimitive(v)
    }
}
