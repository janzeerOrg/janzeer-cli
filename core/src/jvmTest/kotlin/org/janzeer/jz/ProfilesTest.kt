package org.janzeer.jz

import org.janzeer.jz.config.Profiles
import org.janzeer.jz.config.Toml
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProfilesTest {
    @Test fun builtInsAndFileOverlay(@TempDir dir: Path) {
        val cfg = dir.resolve("jz.toml")
        Files.writeString(cfg, """
            default_profile = "local"
            [profiles.local]
            node_dir = "/opt/janzeer"
            [profiles.pi]
            node = "http://192.168.1.112:7019"
            network_id = "janzeer"
        """.trimIndent())
        val s = Profiles.resolve(null, null, cfg.toString())
        assertEquals("local", s.profile.name); assertEquals("http://localhost:7019", s.profile.node); assertEquals("/opt/janzeer", s.profile.nodeDir)
        assertEquals("default_profile", s.source)
        assertEquals("http://192.168.1.112:7019", Profiles.resolve(null, "pi", cfg.toString()).profile.node)
        val custom = Profiles.resolve("https://x.example/", null, cfg.toString())
        assertEquals("custom", custom.profile.name); assertEquals("https://x.example", custom.profile.node); assertEquals(null, custom.profile.networkId)
        assertEquals("testnet", Profiles.resolve("testnet", null, cfg.toString()).profile.name)
        assertFailsWith<JzException> { Profiles.resolve(null, "nope", cfg.toString()) }
        assertFailsWith<JzException> { Profiles.resolve("ftp://x", null, cfg.toString()) }
    }

    @Test fun defaultsWithoutFile(@TempDir dir: Path) {
        val s = Profiles.resolve(null, null, dir.resolve("absent.toml").toString())
        assertEquals("mainnet", s.profile.name); assertEquals("built-in", s.source); assertEquals(false, s.configExists)
        // the generated config parses and reproduces the built-ins
        val parsed = Toml.parse(Profiles.defaultConfig())
        assertEquals("https://explorer.janzeer.org", parsed["profiles.mainnet.node"])
    }

    @Test fun jzMainRunsVersionAndConfigPath(@TempDir dir: Path) {
        val outLines = mutableListOf<String>()
        val code = JzMain.run(arrayOf("version", "--json"), stdout = { outLines += it }, stderr = { })
        assertEquals(0, code)
        assert(outLines.joinToString("\n").contains("\"jz\": \"${BuildInfo.VERSION}\""))
        assertEquals(2, JzMain.run(arrayOf("--bogus"), stdout = { }, stderr = { }))
        assertEquals(0, JzMain.run(arrayOf("--help"), stdout = { }, stderr = { }))
        assertEquals(0, JzMain.run(arrayOf("completion", "bash"), stdout = { }, stderr = { }))
    }
}
