package org.janzeer.jz.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.janzeer.jz.ExitCodes
import org.janzeer.jz.JzException
import org.janzeer.jz.api.CheckResult
import org.janzeer.jz.api.VectorsCheck
import org.janzeer.sdk.Account
import org.janzeer.sdk.Amounts
import org.janzeer.sdk.Janzeer
import org.janzeer.sdk.SpecVersion
import org.janzeer.sdk.crypto.Address
import org.janzeer.sdk.crypto.Bytes
import org.janzeer.sdk.crypto.Hashes
import org.janzeer.sdk.crypto.Hd
import org.janzeer.sdk.crypto.Mnemonic
import org.janzeer.sdk.crypto.Secp256k1
import org.janzeer.sdk.tx.TxBuilder
import org.janzeer.sdk.tx.UnsignedTx
import org.janzeer.sdk.vault.Vault
import org.janzeer.sdk.vault.VaultBlob
import java.nio.file.Files
import java.nio.file.Paths

/** The same assertions as the SDK's VectorsTest and VaultTest, run from the binary against the vendored or a given vector set. */
class SdkVectors : VectorsCheck {
    private fun read(dir: String?, name: String): ByteArray =
        if (dir != null) Files.readAllBytes(Paths.get(dir, name))
        else SdkVectors::class.java.getResourceAsStream("/vectors/$name")?.readBytes() ?: throw JzException(ExitCodes.UNEXPECTED, "vectors", "vendored $name missing from the binary")

    override fun run(dir: String?): List<CheckResult> {
        val out = ArrayList<CheckResult>()
        fun check(name: String, block: () -> Unit) { out += try { block(); CheckResult(name, true) } catch (e: Throwable) { CheckResult(name, false, e.message ?: e.toString()) } }
        fun eq(expected: Any?, actual: Any?, what: String) { if (expected != actual) throw AssertionError("$what: expected $expected, got $actual") }

        val sums = String(read(dir, "SHA256SUMS")).trim().lines()
        for (line in sums) {
            val (sha, name) = line.trim().split(Regex("\\s+"))
            check("checksum $name") { eq(sha, Bytes.toHex(Hashes.sha256(read(dir, name))), name) }
        }
        val v: JsonObject = Json.parseToJsonElement(String(read(dir, "wallet-parity-vectors.json"))).jsonObject
        fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
        fun JsonObject.l(k: String) = this[k]!!.jsonPrimitive.content.toLong()
        fun JsonObject.opt(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        val hd = v["hd"]!!.jsonObject; val rawKey = v["rawKey"]!!.jsonObject
        val signer = Account.fromPrivateKey(rawKey.s("privHex"))

        check("vectors version + network id") { eq(SpecVersion.VECTORS, v.s("vectorsVersion").toInt(), "vectorsVersion"); eq(Janzeer.NETWORK_ID, v.s("networkId"), "networkId") }
        check("mnemonic → seed → m/0/0/0 → address") {
            val seed = Hd.mnemonicToSeed(hd.s("mnemonic"), hd.s("passphrase"))
            eq(hd.s("seedHex"), Bytes.toHex(seed), "seed"); eq(hd.s("privHex"), Bytes.toHex(Hd.deriveDefault(seed).priv), "priv")
            val a = Account.fromMnemonic(hd.s("mnemonic"), hd.s("passphrase"))
            eq(hd.s("pubHex"), a.publicKeyHex, "pub"); eq(hd.s("address"), a.address, "address")
            if (!Mnemonic.validate(hd.s("mnemonic"))) throw AssertionError("mnemonic invalid"); eq(hd.s("mnemonic"), Mnemonic.fromEntropy(Mnemonic.toEntropy(hd.s("mnemonic"))), "entropy round trip")
        }
        check("raw key → address") { eq(rawKey.s("pubHex"), signer.publicKeyHex, "pub"); eq(rawKey.s("address"), signer.address, "address") }
        check("EIP-55 overlay") { for (e in v["addresses"]!!.jsonArray) { val o = e.jsonObject; eq(o.s("checksum"), Address.toChecksum(o.s("lower")), o.s("lower")); if (!Address.isChecksumValid(o.s("checksum"))) throw AssertionError("checksum invalid ${o.s("checksum")}") } }
        check("scaled amounts") { for (e in v["scaled"]!!.jsonArray) { val o = e.jsonObject; eq(o.l("out"), Amounts.toScaledLong(o.s("in")), o.s("in")) } }

        fun tx(name: String, build: (JsonObject) -> UnsignedTx) = check(name) {
            val t = v[name]!!.jsonObject; val u = build(t)
            t.opt("preimageHex")?.let { eq(it, Bytes.toHex(u.preimage()), "preimage") }
            eq(t.s("hash"), u.hash(), "hash")
            val signed = signer.signTx(u)
            eq(t.s("signature"), signed.signature, "signature"); eq(t.s("publicKey"), signed.senderPublicKey, "publicKey")
            if (!signer.verify(signed.hash, signed.signature)) throw AssertionError("own signature does not verify")
            if (Secp256k1.verifyHash(signed.hash, signed.signature, hd.s("pubHex"))) throw AssertionError("signature verifies with the wrong key")
            eq(t.s("hash"), signed.toRestBody()["hash"], "rest hash"); eq(t.s("signature"), signed.toRestBody()["senderSignature"], "rest signature")
        }
        tx("transferTx") { t -> TxBuilder.transfer(from = t.s("senderAddress"), to = t.s("recipientAddress"), amount = t.s("amount"), nonce = t.l("nonce"), data = t.s("data"), fee = t.s("fee"), timestamp = t.l("timestamp")) }
        tx("transferNoMemoTx") { t -> TxBuilder.transfer(from = t.s("senderAddress"), to = t.s("recipientAddress"), amount = t.s("amount"), nonce = t.l("nonce"), fee = t.s("fee"), timestamp = t.l("timestamp")) }
        tx("promoterTx") { t -> TxBuilder.registerValidator(from = t.s("senderAddress"), validatorKey = t.s("promoterKey"), nonce = t.l("nonce"), amount = t.s("amount"), fee = t.s("fee"), timestamp = t.l("timestamp")) }
        tx("exitPromoterTx") { t -> TxBuilder.exitValidator(from = t.s("senderAddress"), validatorKey = t.s("promoterKey"), nonce = t.l("nonce"), fee = t.s("fee"), timestamp = t.l("timestamp")) }
        tx("tokenCreateTx") { t -> TxBuilder.Token.create(from = t.s("senderAddress"), symbol = t.s("symbol"), name = t.s("name"), nonce = t.l("nonce"), decimals = t.s("decimals").toInt(), cap = t.opt("cap"), amount = t.opt("amount"), fee = t.s("fee"), timestamp = t.l("timestamp")) }
        tx("tokenTransferTx") { t -> TxBuilder.Token.transfer(from = t.s("senderAddress"), tokenId = t.s("tokenId"), amount = t.s("amount"), recipient = t.s("recipient"), nonce = t.l("nonce"), fee = t.s("fee"), timestamp = t.l("timestamp")) }

        check("vault fixture decrypts") {
            val f = Json.parseToJsonElement(String(read(dir, "vault-fixture.json"))).jsonObject
            val blob = VaultBlob.fromMap(f["blob"]!!.jsonObject.mapValues { (_, e) -> e.jsonPrimitive.let { if (it.isString) it.content else it.content.toInt() } })
            eq(f.s("secret"), Vault.decrypt(blob, f.s("password")), "secret")
            val wrong = runCatching { Vault.decrypt(blob, f.s("wrongPassword")) }.isSuccess
            if (wrong) throw AssertionError("wrong password was accepted")
        }
        return out
    }
}
