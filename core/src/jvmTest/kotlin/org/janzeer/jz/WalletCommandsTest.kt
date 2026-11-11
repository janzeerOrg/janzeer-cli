package org.janzeer.jz

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletCommandsTest {
    private val canon = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val canonAddress = "0x06e1c0fa9955a700876f8cb0acc7f13fba9fb8ba"

    private fun env(dir: Path): Array<String> {
        val pw = dir.resolve("pw"); Files.writeString(pw, "secret-1\n")
        return arrayOf("--password-file", pw.toString())
    }

    @Test fun lifecycle(@TempDir dir: Path) {
        val cfg = dir.resolve("cfg"); System.setProperty("jz.test.config", cfg.toString())
        withConfigDir(cfg) {
            val s = FakeServices { p -> FakeChain(p, emptyMap()) }
            val imp = jz(s, *env(dir), "wallet", "import", "--name", "canon", "--mnemonic", canon, "--json")
            assertEquals(0, imp.code, imp.err)
            assertEquals(canonAddress, imp.json.jsonObject["address"]!!.jsonPrimitive.content.lowercase())
            val file = Path.of(imp.json.jsonObject["file"]!!.jsonPrimitive.content)
            assertTrue(Files.exists(file))
            if (!System.getProperty("os.name").lowercase().contains("win")) // Windows has no POSIX permission bits
                assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            assertFalse(Files.readString(file).contains("abandon"), "secret must not be in clear")

            val list = jz(s, "wallet", "list")
            assertTrue(list.out.contains("canon") && list.out.lowercase().contains(canonAddress), list.out)

            val show = jz(s, *env(dir), "wallet", "show", "canon", "--verify", "--json")
            assertEquals(canonAddress, show.json.jsonObject["derivedAddress"]!!.jsonPrimitive.content.lowercase())

            val reveal = jz(s, *env(dir), "wallet", "export", "canon", "--reveal", "--yes", "--json")
            assertEquals(canon, reveal.json.jsonObject["secret"]!!.jsonPrimitive.content)
            val noReveal = jz(s, *env(dir), "wallet", "export", "canon")
            assertFalse(noReveal.out.contains("abandon")); assertTrue(noReveal.out.contains("\"ct\""))

            // wrong password → 8; duplicate name → 8; unknown wallet → 8
            val bad = dir.resolve("bad"); Files.writeString(bad, "nope\n")
            assertEquals(ExitCodes.WALLET, jz(s, "--password-file", bad.toString(), "wallet", "show", "canon", "--verify").code)
            assertEquals(ExitCodes.WALLET, jz(s, *env(dir), "wallet", "import", "--name", "canon", "--mnemonic", canon).code)
            assertEquals(ExitCodes.WALLET, jz(s, "wallet", "show", "ghost").code)

            // new wallet shows a 24-word phrase once; the vault round-trips through the blob export + import
            val new = jz(s, *env(dir), "wallet", "new", "--name", "fresh", "--words", "24", "--json")
            assertEquals(0, new.code, new.err)
            assertEquals(24, new.json.jsonObject["mnemonic"]!!.jsonPrimitive.content.split(" ").size)
            val blobFile = dir.resolve("blob.json"); Files.writeString(blobFile, jz(s, "wallet", "export", "fresh", "--blob").out)
            val reimp = jz(s, *env(dir), "wallet", "import", "--name", "fresh2", "--vault-file", blobFile.toString(), "--json")
            assertEquals(0, reimp.code, reimp.err)
            assertEquals(new.json.jsonObject["address"], reimp.json.jsonObject["address"])

            assertEquals(0, jz(s, "wallet", "remove", "fresh2", "--yes").code)
            assertFalse(jz(s, "wallet", "list").out.contains("fresh2"))

            // a wallet file renamed by hand: `remove` deletes the file that was NAMED, never the one whose inner name matches
            val walletsDir = file.parent
            Files.move(walletsDir.resolve("canon.json"), walletsDir.resolve("old.json"))
            assertEquals(0, jz(s, *env(dir), "wallet", "import", "--name", "canon", "--mnemonic", canon).code)
            assertEquals(0, jz(s, "wallet", "remove", "old", "--yes").code)
            assertFalse(Files.exists(walletsDir.resolve("old.json")))
            assertTrue(Files.exists(walletsDir.resolve("canon.json")), "the wallet that was not named must survive")

            // a BIP39-passphrase wallet: the stored address is the passphrase-derived one, so opening it must ask for the
            // passphrase and refuse a wrong one — it used to sign silently from the passphrase-less account
            val ppAddress = s.keys.fromMnemonic(canon, "correct horse").checksumAddress
            val canonFile = walletsDir.resolve("canon.json")
            Files.writeString(canonFile, Files.readString(canonFile).replace(Regex("\"address\"\\s*:\\s*\"[^\"]+\""), "\"address\": \"$ppAddress\""))
            val ppFile = dir.resolve("pp")
            try {
                System.setProperty("jz.env.JZ_PASSPHRASE_FILE", ppFile.toString())
                Files.writeString(ppFile, "correct horse\n")
                val ok = jz(s, *env(dir), "wallet", "show", "canon", "--verify", "--json")
                assertEquals(0, ok.code, ok.err)
                assertEquals(ppAddress, ok.json.jsonObject["derivedAddress"]!!.jsonPrimitive.content)
                Files.writeString(ppFile, "wrong\n")
                assertEquals(ExitCodes.WALLET, jz(s, *env(dir), "wallet", "show", "canon", "--verify").code)
                assertEquals(ExitCodes.WALLET, jz(s, *env(dir), "wallet", "export", "canon", "--reveal", "--yes").code)
            } finally {
                System.clearProperty("jz.env.JZ_PASSPHRASE_FILE")
            }
        }
    }

    @Test fun keyDeriveAndSignVerify(@TempDir dir: Path) {
        val s = FakeServices { p -> FakeChain(p, emptyMap()) }
        val d = jz(s, "--json", "key", "derive", "--mnemonic", canon, "--reveal", "--yes").json.jsonObject
        assertEquals(canonAddress, d["address"]!!.jsonPrimitive.content.lowercase())
        val priv = d["privateKey"]!!.jsonPrimitive.content; val pub = d["publicKey"]!!.jsonPrimitive.content
        val hash = "aa".repeat(32)
        val sig = jz(s, "key", "sign", "--hash", hash, "--private-key-stdin").let { it } // stdin empty → usage error
        assertEquals(2, sig.code)
        val signed = s.keys.signHash(hash, priv)
        assertEquals(0, jz(s, "key", "verify", "--hash", hash, "--signature", signed, "--public-key", pub).code)
        assertEquals(ExitCodes.TX_REJECTED, jz(s, "key", "verify", "--hash", "bb".repeat(32), "--signature", signed, "--public-key", pub).code)
        assertEquals(0, jz(s, "key", "address", pub).let { assertTrue(it.out.lowercase().contains(canonAddress)); it.code })
    }

    private fun withConfigDir(dir: Path, block: () -> Unit) {
        // Platform reads JZ_CONFIG_DIR from the environment; tests set it through the Java property fallback below.
        System.setProperty("jz.config.dir", dir.toString())
        try { block() } finally { System.clearProperty("jz.config.dir") }
    }
}
