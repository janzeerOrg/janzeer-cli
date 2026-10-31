package org.janzeer.jz

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WriteCommandsTest {
    private val canon = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val info = """{"networkId":"janzeer","version":"0.1.10","apiVersion":"1.1.0","protocolVersion":"3.4.0"}"""
    private val recipient = "0x598b1301acef3baba6ce25e38dd17b723f7b98b1"

    private fun services(nonce: String = "7", rule: String = "minimum", fee: String = "0.01", mismatch: Boolean = false) = FakeServices { p ->
        FakeChain(p, mapOf("janzeer_getNonce" to nonce, "janzeer_estimateFee" to """{"kind":"transfer","fee":$fee,"rule":"$rule","feeMarket":false}""",
            "janzeer_getChainSpec" to """{"validatorDeposit":2000}"""), mapOf("info" to info)).also { c ->
            c.postAnswer = """{"hash":"x","status":"PENDING"}"""
            // the node's exact fees for the fixed-fee kinds (what janzeer_estimateFee really answers)
            c.rpcFn = { m, params -> if (m == "janzeer_estimateFee") when (params.toString()) {
                "[\"tokenCreate\"]" -> """{"kind":"tokenCreate","fee":5,"rule":"exact","feeMarket":false}"""
                "[\"registerValidator\"]" -> """{"kind":"registerValidator","fee":3,"rule":"exact","feeMarket":false}"""
                else -> null } else null }
            if (mismatch) c.throwOnPost = { n -> if (n == 0) org.janzeer.jz.sdk.Errors.map(org.janzeer.sdk.client.NonceMismatchException("Invalid nonce for 0x06e1c0fa9955a700876f8cb0acc7f13fba9fb8ba: expected 9, got 7", "0x06", 9, 7, org.janzeer.sdk.client.Transport.REST)) else null }
        }
    }

    private fun prep(dir: Path, s: FakeServices): Array<String> {
        System.setProperty("jz.config.dir", dir.resolve("cfg").toString())
        val pw = dir.resolve("pw"); Files.writeString(pw, "pw\n")
        assertEquals(0, jz(s, "--password-file", pw.toString(), "wallet", "import", "--name", "main", "--mnemonic", canon).code)
        return arrayOf("--password-file", pw.toString())
    }

    @Test fun dryRunBuildsTheRestBodyWithoutPosting(@TempDir dir: Path) {
        val s = services(); val env = prep(dir, s)
        val r = jz(s, *env, "--json", "send", recipient, "1.25", "--memo", "hi", "--dry-run")
        assertEquals(0, r.code, r.err)
        val j = r.json.jsonObject
        assertEquals("transactions/transfers", j["path"]!!.jsonPrimitive.content)
        val body = j["body"]!!.jsonObject
        assertEquals("0x06e1c0fa9955a700876f8cb0acc7f13fba9fb8ba", body["senderAddress"]!!.jsonPrimitive.content)
        assertEquals("7", body["nonce"]!!.jsonPrimitive.content); assertEquals("hi", body["data"]!!.jsonPrimitive.content)
        assertEquals(recipient, body["recipientAddress"]!!.jsonPrimitive.content.lowercase())
        assertTrue(body.containsKey("senderSignature") && body.containsKey("hash"), body.toString())
        assertTrue(s.lastChain!!.posted.isEmpty())
        // an explicit nonce is honoured
        val again = jz(s, *env, "--json", "send", recipient, "1.25", "--dry-run", "--nonce", "42").json.jsonObject
        assertEquals("42", again["body"]!!.jsonObject["nonce"]!!.jsonPrimitive.content)
    }

    @Test fun sendPostsAndPrintsHash(@TempDir dir: Path) {
        val s = services(); val env = prep(dir, s)
        val r = jz(s, *env, "send", recipient, "2", "--yes")
        assertEquals(0, r.code, r.err)
        assertEquals(1, s.lastChain!!.posted.size)
        assertTrue(r.out.contains("accepted by the node — hash "), r.out)
        assertTrue(r.out.contains("fee   0.01 JNZ") || r.out.contains("0.01 JNZ"), r.out)
    }

    @Test fun feeRules(@TempDir dir: Path) {
        val s = services(fee = "0.01"); val env = prep(dir, s)
        var j = jz(s, *env, "--json", "send", recipient, "1", "--dry-run", "--fee", "0.5").json.jsonObject["body"]!!.jsonObject
        assertEquals("0.5", j["fee"]!!.jsonPrimitive.content)
        j = jz(s, *env, "--json", "send", recipient, "1", "--dry-run", "--fee", "0.001").json.jsonObject["body"]!!.jsonObject
        assertEquals("0.01", j["fee"]!!.jsonPrimitive.content) // below minimum → minimum
        val exact = services(rule = "exact", fee = "3"); prep(dir.resolve("b").also { Files.createDirectories(it) }, exact)
        j = jz(exact, "--password-file", dir.resolve("b/pw").toString(), "--json", "send", recipient, "1", "--dry-run", "--fee", "9").json.jsonObject["body"]!!.jsonObject
        assertEquals("3", j["fee"]!!.jsonPrimitive.content) // exact wins
        assertEquals(2, jz(s, *env, "send", recipient, "1", "--fee", "abc", "--dry-run").code)
        assertEquals(2, jz(s, *env, "send", "0x12", "1", "--dry-run").code)
        assertEquals(2, jz(s, *env, "send", recipient, "1.123456789", "--dry-run").code)
    }

    @Test fun nonceMismatchIsRetriedOnce(@TempDir dir: Path) {
        val s = services(mismatch = true); val env = prep(dir, s)
        val r = jz(s, *env, "--json", "send", recipient, "1", "--yes")
        assertEquals(0, r.code, r.err)
        assertEquals(2, s.lastChain!!.posted.size)
        assertEquals("9", s.lastChain!!.posted[1].second.jsonObject["nonce"]!!.jsonPrimitive.content)
        assertTrue(r.err.contains("expects 9"), r.err)
        // an explicit --nonce is never silently replaced
        val s2 = services(mismatch = true); val env2 = prep(dir.resolve("c").also { Files.createDirectories(it) }, s2)
        assertEquals(ExitCodes.TX_REJECTED, jz(s2, *env2, "send", recipient, "1", "--yes", "--nonce", "7").code)
    }

    @Test fun validatorRegisterNeedsTypedConfirmationAndCarriesTheDeposit(@TempDir dir: Path) {
        val s = services(); val env = prep(dir, s)
        val r = jz(s, *env, "--json", "validator", "register", "--key", "03f7c8080674cb04944170b11730cca5371ebfc702592be9cc5d1ad78771b8c390", "--dry-run")
        assertEquals(0, r.code, r.err)
        val body = r.json.jsonObject["body"]!!.jsonObject
        assertEquals("transactions/validators", r.json.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("2000", body["amount"]!!.jsonPrimitive.content)
        // without --yes and without a terminal the confirmation is refused (exit 2) and nothing is posted
        assertEquals(2, jz(s, *env, "validator", "register", "--key", "03f7c8080674cb04944170b11730cca5371ebfc702592be9cc5d1ad78771b8c390").code)
        assertTrue(s.lastChain!!.posted.isEmpty())
        assertEquals(2, jz(s, *env, "validator", "register", "--key", "deadbeef", "--dry-run").code)
    }

    @Test fun tokenOps(@TempDir dir: Path) {
        val s = services(); val env = prep(dir, s)
        val c = jz(s, *env, "--json", "token", "create", "--symbol", "JZX", "--name", "Test", "--decimals", "2", "--cap", "1000000", "--mint", "5000", "--dry-run")
        assertEquals(0, c.code, c.err)
        assertEquals("transactions/tokens", c.json.jsonObject["path"]!!.jsonPrimitive.content)
        val body = c.json.jsonObject["body"]!!.jsonObject
        assertEquals("JZX", body["symbol"]!!.jsonPrimitive.content); assertEquals("5", body["fee"]!!.jsonPrimitive.content); assertEquals("5000", body["amount"]!!.jsonPrimitive.content)
        val t = jz(s, *env, "--json", "token", "transfer", "c763f2c795cb723369300068ad59219469b39fede0845d9353119034affead9f", "50", recipient, "--dry-run")
        assertEquals(0, t.code, t.err)
        assertEquals(recipient, t.json.jsonObject["body"]!!.jsonObject["recipient"]!!.jsonPrimitive.content.lowercase())
        assertEquals(2, jz(s, *env, "token", "create", "--symbol", "JZX", "--name", "x", "--decimals", "30", "--dry-run").code)
    }
}
