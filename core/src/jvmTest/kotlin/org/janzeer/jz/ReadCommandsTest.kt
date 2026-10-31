package org.janzeer.jz

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadCommandsTest {
    private val tip = """{"type":"main","height":170521,"hash":"9e25331fe839b6dbd0c7095e7318e0d8f889760ca6489c356888547dec65be46","previousHash":"9cee2264","timestamp":1793456707577,"producer":"022ee09747ae0c889ec63bef925aff17dbbb726a30c549578c7f5b09a130a2950d","epochIndex":11,"transactionsCount":1}"""
    private val tx = """{"hash":"c763f2c795cb723369300068ad59219469b39fede0845d9353119034affead9f","type":"reward","status":"FINAL","timestamp":1793448247590,"fee":0E-8,"nonce":0,"senderAddress":"0x0000000000000000000000000000000000000000","blockHash":"adfaea4a","blockHeight":170000,"amount":10.00000000,"recipientAddress":"0xf31058f41f598ff746f2ff89c5993781d7711607","receipt":{"successful":true,"results":[]}}"""
    private val account = """{"address":"0xf5183df4a441b890dd8324b6642bcfe5d3e2351a","balance":1366080.00000000,"committedBalance":1366080.00000000,"nextNonce":3,"committedNonce":3,"pendingCount":0,"isValidatorWallet":true}"""
    private val validators = """{"total":1,"page":0,"pageSize":50,"totalPages":1,"list":[{"key":"03f7c8080674cb04944170b11730cca5371ebfc702592be9cc5d1ad78771b8c390","walletAddress":"0xf5183df4a441b890dd8324b6642bcfe5d3e2351a","registeredAt":1751468842559,"active":true,"banned":false,"exited":false}]}"""
    private lateinit var services: FakeServices

    @BeforeEach fun setUp(@TempDir dir: Path) {
        System.setProperty("jz.test.config", dir.toString())
        services = FakeServices { p -> FakeChain(p, mapOf(
            "janzeer_getTip" to tip, "janzeer_getBlockByNumber" to tip, "janzeer_getTransactionByHash" to tx, "janzeer_getAccount" to account,
            "janzeer_getBalance" to "1366080.00000000", "janzeer_getNonce" to "3", "janzeer_listValidators" to validators,
            "janzeer_getTransactionByHash_unknown" to """{"hash":"00","status":"UNKNOWN"}""",
        )) }
    }

    @Test fun blockTipHumanAndJson() {
        val r = jz(services, "block", "tip", "--no-color")
        assertEquals(0, r.code, r.err)
        assertTrue(r.out.contains("#170521") && r.out.contains("9e25331fe839b6dbd0c7095e7318e0d8f889760ca6489c356888547dec65be46"), r.out)
        val j = jz(services, "--json", "block", "tip")
        assertEquals(170521L, j.json.jsonObject["height"]!!.jsonPrimitive.content.toLong())
        assertEquals("janzeer_getTip", services.lastChain!!.calls.single().first)
    }

    @Test fun blockByHeightPassesParams() {
        jz(services, "block", "170000", "--txs")
        val (m, p) = services.lastChain!!.calls.single()
        assertEquals("janzeer_getBlockByNumber", m); assertEquals("[170000,true]", p.toString())
    }

    @Test fun txDetailAndAmounts() {
        val r = jz(services, "tx", "c763f2c795cb723369300068ad59219469b39fede0845d9353119034affead9f")
        assertEquals(0, r.code, r.err)
        assertTrue(r.out.contains("10 JNZ") && !r.out.contains("JNZ JNZ"), r.out)
        assertTrue(r.out.contains("0 JNZ"), r.out) // fee 0E-8 normalised
        assertEquals(2, jz(services, "tx", "nothex").code)
    }

    @Test fun addressBalanceNonce() {
        val r = jz(services, "address", "0xf5183df4a441b890dd8324b6642bcfe5d3e2351a")
        assertEquals(0, r.code, r.err)
        assertTrue(r.out.contains("1,366,080 JNZ") || r.out.contains("1366080 JNZ"), r.out)
        assertTrue(Regex("next nonce\\s+3").containsMatchIn(r.out), r.out)
        assertEquals("1366080.00000000\n", jz(services, "balance", "0xf5183df4a441b890dd8324b6642bcfe5d3e2351a").out)
        assertEquals("3\n", jz(services, "nonce", "0xf5183df4a441b890dd8324b6642bcfe5d3e2351a").out)
        assertEquals(2, jz(services, "balance", "0x1234").code)
    }

    @Test fun validatorsTableWithoutSubcommand() {
        val r = jz(services, "validators")
        assertEquals(0, r.code, r.err)
        assertTrue(r.out.contains("03f7c8080674cb04944170b11730cca5371ebfc702592be9cc5d1ad78771b8c390") && r.out.contains("active"), r.out)
        assertEquals(1, jz(services, "--json", "validators").json.jsonObject["list"]!!.jsonArray.size)
    }

    @Test fun notFoundExitCodes() {
        val empty = FakeServices { p -> FakeChain(p, mapOf("janzeer_getTransactionByHash" to """{"hash":"00","status":"UNKNOWN"}""")) }
        val r = jz(empty, "tx", "0000000000000000000000000000000000000000000000000000000000000000")
        assertEquals(ExitCodes.NOT_FOUND, r.code); assertTrue(r.err.contains("no transaction"), r.err)
        assertEquals(ExitCodes.NOT_FOUND, jz(empty, "block", "5").code)
        val j = jz(empty, "--json", "block", "5")
        assertTrue(j.err.contains("\"code\": 6"), j.err)
    }

    @Test fun sdkErrorsMapToExitCodes() {
        val failing = FakeServices { p -> FakeChain(p, emptyMap()).also { c ->
            c.throwOnRpc = { m -> when (m) {
                "janzeer_getTip" -> org.janzeer.jz.sdk.Errors.map(org.janzeer.sdk.client.NetworkException("boom"))
                else -> null
            } }
        } }
        assertEquals(ExitCodes.NETWORK, jz(failing, "block", "tip").code)
        assertEquals(ExitCodes.NODE_BUSY, org.janzeer.jz.sdk.Errors.map(org.janzeer.sdk.client.RateLimitedException("x", 1000, org.janzeer.sdk.client.Transport.RPC)).exitCode)
        assertEquals(ExitCodes.TX_REJECTED, org.janzeer.jz.sdk.Errors.map(org.janzeer.sdk.client.NonceMismatchException("Invalid nonce for 0xf5183df4a441b890dd8324b6642bcfe5d3e2351a: expected 3, got 1", "0xf5", 3, 1, org.janzeer.sdk.client.Transport.REST)).exitCode)
        assertEquals(ExitCodes.FINALITY_TIMEOUT, org.janzeer.jz.sdk.Errors.map(org.janzeer.sdk.client.FinalityTimeoutException("ab", null)).exitCode)
        assertEquals(ExitCodes.NOT_FOUND, org.janzeer.jz.sdk.Errors.map(org.janzeer.sdk.client.NotFoundException(404, "x")).exitCode)
    }
}
