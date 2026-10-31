package org.janzeer.jz

import org.janzeer.jz.config.Toml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TomlTest {
    @Test fun parsesTheSubset() {
        val m = Toml.parse("""
            # comment
            default_profile = "mainnet"   # trailing comment
            count = 42
            flag = true
            [profiles.mainnet]
            node = "https://explorer.janzeer.org"
            [profiles.local]
            node = "http://localhost:7019"
            note = "a \"quoted\" # not a comment"
        """.trimIndent())
        assertEquals("mainnet", m["default_profile"])
        assertEquals(42L, m["count"])
        assertEquals(true, m["flag"])
        assertEquals("https://explorer.janzeer.org", m["profiles.mainnet.node"])
        assertEquals("http://localhost:7019", m["profiles.local.node"])
        assertEquals("a \"quoted\" # not a comment", m["profiles.local.note"])
    }

    @Test fun rejectsWhatItDoesNotSupport() {
        assertFailsWith<JzException> { Toml.parse("x = [1, 2]") }
        assertFailsWith<JzException> { Toml.parse("[[arr]]\nx = 1") }
        assertFailsWith<JzException> { Toml.parse("x = \"open") }
        assertFailsWith<JzException> { Toml.parse("= 1") }
    }

    @Test fun hoistsGlobalOptionsInFrontOfTheSubcommand() {
        assertEquals(listOf("--json", "--node", "http://h", "info"), Jz.hoistGlobals(listOf("info", "--json", "--node", "http://h")))
        assertEquals(listOf("--profile=local", "send", "0xabc", "1", "--memo", "hi"), Jz.hoistGlobals(listOf("send", "0xabc", "--profile=local", "1", "--memo", "hi")))
        assertEquals(listOf("-q", "node", "logs", "--", "--json"), Jz.hoistGlobals(listOf("node", "-q", "logs", "--", "--json")))
    }

    @Test fun renderRoundTrips() {
        val values = linkedMapOf<String, Any>("a" to "x", "n" to 1L, "profiles.p.node" to "http://h", "profiles.p.on" to true)
        assertEquals(values, Toml.parse(Toml.render(values)))
    }
}
