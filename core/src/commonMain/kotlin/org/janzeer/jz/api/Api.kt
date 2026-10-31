package org.janzeer.jz.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.janzeer.jz.config.Profile

/**
 * The seams between the command tree (commonMain) and the SDK (jvmMain). Commands only see these; tests fake them.
 * Everything crosses as kotlinx `JsonElement` trees — the node's own field names, no second model layer.
 */
interface SdkInfo {
    val sdkNode: String
    val sdkApi: String
    val sdkProtocol: String
    val sdkVectors: Int
    val sdkVersion: String
}

/** One node connection. Failures surface as [org.janzeer.jz.JzException] (the adapter maps the SDK's exceptions). */
interface ChainApi : AutoCloseable {
    val profile: Profile
    /** REST under `/api/v1/`: the unwrapped payload, or null for 404. */
    fun get(path: String, query: Map<String, Any?> = emptyMap()): JsonElement?
    /** REST POST under `/api/v1/`: the unwrapped payload. */
    fun post(path: String, body: JsonElement): JsonElement?
    /** Any absolute URL on the node (actuator): parsed body, or null for 404. */
    fun getAbsolute(url: String): JsonElement?
    /** JSON-RPC `janzeer_<method>`; the `result` member. */
    fun rpc(method: String, params: JsonElement? = null): JsonElement?
    /** WebSocket subscription; [onEvent] runs on the socket thread. Close the handle to unsubscribe. */
    fun subscribe(kind: String, params: JsonObject, onEvent: (JsonElement) -> Unit, onStatus: (String) -> Unit): AutoCloseable
    /** Long-poll `janzeer_waitForFinality` up to [timeoutMs]; the final tx view, or throws finality-timeout. */
    fun waitForFinality(hash: String, timeoutMs: Long): JsonElement
}

/** Derived key material, hex without `0x` for keys, `0x…` for the address. */
data class KeyInfo(val address: String, val checksumAddress: String, val publicKeyHex: String, val privateKeyHex: String)

/** A built and signed transaction, ready to POST: [body] is the node's REST request body. */
data class SignedTxData(val hash: String, val body: JsonObject, val restPath: String)

/** Key, signing and vault operations — the SDK's `Account`, `Mnemonic`, `Secp256k1`, `Vault`, `TxBuilder`. */
interface KeyOps {
    fun generateMnemonic(words: Int): String
    fun validateMnemonic(mnemonic: String): Boolean
    fun normalizeMnemonic(mnemonic: String): String
    fun fromMnemonic(mnemonic: String, passphrase: String): KeyInfo
    fun fromPrivateKey(privateKeyHex: String): KeyInfo
    fun isValidPrivateKey(hex: String): Boolean
    fun isValidAddress(address: String): Boolean
    fun checksumAddress(address: String): String
    /** Address of a 33-byte compressed public key (hex). */
    fun addressOfPublicKey(publicKeyHex: String): String
    fun signHash(hashHex: String, privateKeyHex: String): String
    fun verifyHash(hashHex: String, signatureB64: String, publicKeyHex: String): Boolean
    /** SDK vault blob `{v,salt,iv,ct}`; interoperable with the other SDKs. */
    fun encryptVault(secret: String, password: String): JsonObject
    fun decryptVault(blob: JsonObject, password: String): String
    /** `Amounts.formatJnz`: grouped, trailing zeros trimmed. */
    fun formatJnz(amount: String): String
    fun isValidAmount(amount: String): Boolean
    fun normalizeAmount(amount: String): String
    /** Numeric comparison of two decimal strings. */
    fun compareAmounts(a: String, b: String): Int
    /** Build + sign one transaction. [kind] = transfer | token | registerValidator | exitValidator; [fields] are the builder inputs. */
    fun buildAndSign(kind: String, fields: JsonObject, privateKeyHex: String): SignedTxData
}

/** One conformance check: name, pass/fail, detail on failure. */
data class CheckResult(val name: String, val ok: Boolean, val detail: String? = null)

/** Reproduces the public conformance vectors with the SDK at runtime (`jz vectors check`). */
interface VectorsCheck {
    /** [dir] = a directory holding wallet-parity-vectors.json + vault-fixture.json + SHA256SUMS; null = the vendored copies. */
    fun run(dir: String?): List<CheckResult>
}

/** Factory for everything that talks to a node or the disk; one per process. */
interface Services {
    val sdk: SdkInfo
    val keys: KeyOps
    val vectors: VectorsCheck
    fun chain(profile: Profile, timeoutSec: Int): ChainApi
}
