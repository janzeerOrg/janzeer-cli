package org.janzeer.jz.cmd

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.janzeer.jz.ExitCodes
import org.janzeer.jz.JzContext
import org.janzeer.jz.JzException

class VectorsCmd : CliktCommand(name = "vectors") {
    override fun help(context: Context) = "Conformance self-test: reproduce the public Janzeer vectors (keys, addresses, every transaction preimage and signature, the vault)"
    init { subcommands(Check()) }
    override fun run() = Unit

    class Check : CliktCommand(name = "check") {
        override fun help(context: Context) = "Run every vector; exit 1 on any mismatch"
        private val ctx by requireObject<JzContext>()
        private val dir by option("--dir", metavar = "PATH", help = "Vector set to use instead of the vendored copy (sdk_conformance/vectors)")
        override fun run() {
            val results = ctx.services.vectors.run(dir)
            ctx.out.data(buildJsonArray { results.forEach { r -> add(buildJsonObject { put("check", r.name); put("ok", r.ok); if (r.detail != null) put("detail", r.detail) }) } })
            results.forEach { r -> ctx.out.line((if (r.ok) "  ok    " else "  FAIL  ") + r.name + (r.detail?.let { "  — $it" } ?: "")) }
            val failed = results.count { !it.ok }
            ctx.out.line(); ctx.out.line("${results.size - failed} of ${results.size} checks passed" + (dir?.let { "  ($it)" } ?: "  (vendored vectors v${ctx.services.sdk.sdkVectors})"))
            if (failed > 0) throw JzException(ExitCodes.UNEXPECTED, "vectors", "$failed conformance check(s) failed")
        }
    }
}
