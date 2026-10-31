package org.janzeer.jz

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.janzeer.jz.api.ChainApi
import org.janzeer.jz.api.Services
import org.janzeer.jz.config.Profile
import org.janzeer.jz.sdk.SdkKeyOps
import org.janzeer.jz.sdk.SdkServices

/** Real keys/crypto (the SDK), fake node: canned JSON per RPC method / REST path, recorded calls. */
class FakeChain(override val profile: Profile, private val rpcAnswers: Map<String, String>, private val restAnswers: Map<String, String> = emptyMap()) : ChainApi {
    val calls = mutableListOf<Pair<String, JsonElement?>>()
    val posted = mutableListOf<Pair<String, JsonElement>>()
    var postAnswer: String = "{}"
    var finality: String? = null
    var throwOnRpc: ((String) -> Throwable?)? = null
    /** Answer computed from method + params; wins over [rpcAnswers] when it returns non-null. */
    var rpcFn: ((String, JsonElement?) -> String?)? = null
    /** Called with the post index (0 = first); a non-null result is thrown instead of answering. */
    var throwOnPost: ((Int) -> Throwable?)? = null
    override fun get(path: String, query: Map<String, Any?>): JsonElement? { calls += "GET $path" to null; return restAnswers[path]?.let(Json::parseToJsonElement) }
    override fun post(path: String, body: JsonElement): JsonElement? {
        val n = posted.size; posted += path to body
        throwOnPost?.invoke(n)?.let { throw it }
        return Json.parseToJsonElement(postAnswer)
    }
    override fun getAbsolute(url: String): JsonElement? { calls += "GET $url" to null; return restAnswers[url]?.let(Json::parseToJsonElement) }
    override fun rpc(method: String, params: JsonElement?): JsonElement? {
        calls += method to params
        throwOnRpc?.invoke(method)?.let { throw it }
        rpcFn?.invoke(method, params)?.let { return Json.parseToJsonElement(it) }
        return rpcAnswers[method]?.let(Json::parseToJsonElement)
    }
    override fun subscribe(kind: String, params: JsonObject, onEvent: (JsonElement) -> Unit, onStatus: (String) -> Unit): AutoCloseable = AutoCloseable { }
    override fun waitForFinality(hash: String, timeoutMs: Long): JsonElement = Json.parseToJsonElement(finality ?: rpcAnswers["janzeer_getTransactionByHash"]!!)
    override fun close() { }
}

class FakeServices(val chainFactory: (Profile) -> FakeChain) : Services {
    private val real = SdkServices()
    override val sdk = real.sdk
    override val keys = SdkKeyOps()
    var lastChain: FakeChain? = null
    override fun chain(profile: Profile, timeoutSec: Int): ChainApi = chainFactory(profile).also { lastChain = it }
}

/** Runs jz with fakes; captures stdout/stderr. */
class Run(val code: Int, val out: String, val err: String) {
    val json: JsonElement get() = Json.parseToJsonElement(out)
}
fun jz(services: Services, vararg args: String): Run {
    val o = StringBuilder(); val e = StringBuilder()
    val code = JzMain.run(arrayOf(*args), services, { o.append(it).append('\n') }, { e.append(it).append('\n') })
    return Run(code, o.toString(), e.toString())
}
