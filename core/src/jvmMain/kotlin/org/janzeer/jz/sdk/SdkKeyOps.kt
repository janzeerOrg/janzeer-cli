package org.janzeer.jz.sdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.janzeer.jz.ExitCodes
import org.janzeer.jz.JzException
import org.janzeer.jz.api.KeyInfo
import org.janzeer.jz.api.KeyOps
import org.janzeer.jz.api.SignedTxData
import org.janzeer.sdk.Account
import org.janzeer.sdk.Amounts
import org.janzeer.sdk.client.JsonUtil
import org.janzeer.sdk.crypto.Address
import org.janzeer.sdk.crypto.Mnemonic
import org.janzeer.sdk.crypto.Secp256k1
import org.janzeer.sdk.tx.TxBuilder
import org.janzeer.sdk.tx.UnsignedTx
import org.janzeer.sdk.vault.Vault
import org.janzeer.sdk.vault.VaultBlob

/** Keys, signing, vault and transaction building — every line delegates to the published SDK; nothing is re-implemented. */
class SdkKeyOps : KeyOps {
    override fun generateMnemonic(words: Int): String {
        val strength = when (words) { 12 -> 128; 15 -> 160; 18 -> 192; 21 -> 224; 24 -> 256; else -> throw JzException(ExitCodes.USAGE, "usage", "--words must be 12, 15, 18, 21 or 24") }
        return Mnemonic.generate(strength)
    }
    override fun validateMnemonic(mnemonic: String): Boolean = Mnemonic.validate(mnemonic)
    override fun normalizeMnemonic(mnemonic: String): String = Mnemonic.normalize(mnemonic)

    override fun fromMnemonic(mnemonic: String, passphrase: String): KeyInfo {
        if (!Mnemonic.validate(mnemonic)) throw JzException(ExitCodes.WALLET, "mnemonic", "not a valid BIP39 English phrase (word list or checksum)")
        return Account.fromMnemonic(mnemonic, passphrase).info()
    }
    override fun fromPrivateKey(privateKeyHex: String): KeyInfo {
        val hex = privateKeyHex.removePrefix("0x")
        if (!Secp256k1.isValidPrivateKey(hex)) throw JzException(ExitCodes.WALLET, "key", "not a valid secp256k1 private key (64 hex chars, 1 ≤ k < n)")
        return Account.fromPrivateKey(hex).info()
    }
    private fun Account.info() = KeyInfo(address, checksumAddress, publicKeyHex, privateKeyHex)

    override fun isValidPrivateKey(hex: String): Boolean = Secp256k1.isValidPrivateKey(hex.removePrefix("0x"))
    override fun isValidAddress(address: String): Boolean = Address.isValid(address)
    override fun checksumAddress(address: String): String = Address.toChecksum(address)
    override fun addressOfPublicKey(publicKeyHex: String): String = Address.toChecksum(Address.fromPublicKey(org.janzeer.sdk.crypto.Bytes.fromHex(publicKeyHex.removePrefix("0x"))))
    override fun signHash(hashHex: String, privateKeyHex: String): String = Secp256k1.signHash(hashHex.removePrefix("0x"), privateKeyHex.removePrefix("0x"))
    override fun verifyHash(hashHex: String, signatureB64: String, publicKeyHex: String): Boolean = Secp256k1.verifyHash(hashHex.removePrefix("0x"), signatureB64, publicKeyHex.removePrefix("0x"))

    override fun encryptVault(secret: String, password: String): JsonObject = JsonUtil.toElement(Vault.encrypt(secret, password).toMap()) as JsonObject
    override fun decryptVault(blob: JsonObject, password: String): String {
        val map = blob.mapValues { (_, v) -> (v as? JsonPrimitive)?.let { if (it.isString) it.content else it.content.toIntOrNull() ?: it.content } ?: v.toString() }
        return Vault.decrypt(VaultBlob.fromMap(map), password)
    }

    override fun formatJnz(amount: String): String = Amounts.formatJnz(java.math.BigDecimal(amount).toPlainString())
    override fun isValidAmount(amount: String): Boolean = Amounts.isValid(amount)
    override fun normalizeAmount(amount: String): String = Amounts.normalize(amount)

    /**
     * [fields] keys follow the SDK builder parameters: `from`, `nonce`, `fee`, `networkId`, `timestamp?` plus per kind
     * transfer: `to`, `amount`, `data?` · registerValidator: `validatorKey`, `amount?` · exitValidator: `validatorKey`
     * token: `op` (create|mint|burn|setcap|transfer), `symbol`, `name`, `decimals`, `cap`, `amount`, `tokenId`, `recipient`.
     */
    override fun buildAndSign(kind: String, fields: JsonObject, privateKeyHex: String): SignedTxData {
        fun s(k: String): String = fields[k]?.jsonPrimitive?.content ?: throw JzException(ExitCodes.USAGE, "tx", "missing field '$k'")
        fun opt(k: String): String? = fields[k]?.jsonPrimitive?.content
        val from = s("from"); val nonce = s("nonce").toLong(); val fee = s("fee"); val networkId = s("networkId")
        val ts = opt("timestamp")?.toLong() ?: System.currentTimeMillis()
        val unsigned: UnsignedTx = when (kind) {
            "transfer" -> TxBuilder.transfer(from, s("to"), s("amount"), nonce, opt("data"), fee, ts, networkId)
            "registerValidator" -> TxBuilder.registerValidator(from, s("validatorKey"), nonce, opt("amount") ?: org.janzeer.sdk.Janzeer.VALIDATOR_DEPOSIT, fee, ts, networkId)
            "exitValidator" -> TxBuilder.exitValidator(from, s("validatorKey"), nonce, fee, ts, networkId)
            "token" -> when (val op = s("op")) {
                "create" -> TxBuilder.Token.create(from, s("symbol"), s("name"), nonce, opt("decimals")?.toInt() ?: 0, opt("cap"), opt("amount"), fee, ts, networkId)
                "mint" -> TxBuilder.Token.mint(from, s("tokenId"), s("amount"), nonce, opt("recipient"), fee, ts, networkId)
                "burn" -> TxBuilder.Token.burn(from, s("tokenId"), s("amount"), nonce, fee, ts, networkId)
                "setcap" -> TxBuilder.Token.setCap(from, s("tokenId"), s("cap"), nonce, fee, ts, networkId)
                "transfer" -> TxBuilder.Token.transfer(from, s("tokenId"), s("amount"), s("recipient"), nonce, fee, ts, networkId)
                else -> throw JzException(ExitCodes.USAGE, "tx", "unknown token op '$op'")
            }
            else -> throw JzException(ExitCodes.USAGE, "tx", "unknown transaction kind '$kind'")
        }
        val signed = Account.fromPrivateKey(privateKeyHex.removePrefix("0x")).signTx(unsigned)
        return SignedTxData(signed.hash, JsonUtil.toElement(signed.toRestBody()) as JsonObject, signed.restPath)
    }
}
