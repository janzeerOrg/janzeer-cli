package org.janzeer.jz.cmd

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.requireObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.janzeer.jz.BuildInfo
import org.janzeer.jz.JzContext

class VersionCmd : CliktCommand(name = "version") {
    override fun help(context: Context) = "Version of jz and of the SDK it is built on"
    private val ctx by requireObject<JzContext>()

    override fun run() {
        val s = ctx.services.sdk
        ctx.out.data(buildJsonObject {
            put("jz", BuildInfo.VERSION); put("sdk", s.sdkVersion)
            put("node", s.sdkNode); put("api", s.sdkApi); put("protocol", s.sdkProtocol); put("vectors", s.sdkVectors)
        })
        ctx.out.line("jz ${BuildInfo.VERSION}")
        ctx.out.kv(listOf(
            "SDK" to "janzeer-sdk-kotlin ${s.sdkVersion}",
            "built for node" to s.sdkNode,
            "API / protocol" to "${s.sdkApi} / ${s.sdkProtocol}",
            "vectors" to "v${s.sdkVectors}",
        ))
    }
}
