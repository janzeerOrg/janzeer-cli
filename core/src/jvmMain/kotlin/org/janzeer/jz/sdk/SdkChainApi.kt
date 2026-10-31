package org.janzeer.jz.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.janzeer.jz.ExitCodes
import org.janzeer.jz.JzException
import org.janzeer.jz.api.ChainApi
import org.janzeer.jz.config.Profile
import org.janzeer.sdk.client.Helpers
import org.janzeer.sdk.client.JanzeerClient
import org.janzeer.sdk.client.JsonUtil
import org.janzeer.sdk.rpc.BlockView
import org.janzeer.sdk.rpc.JanzeerRpc
import org.janzeer.sdk.rpc.JanzeerRpcWs
import org.janzeer.sdk.rpc.NewBlocksParams
import org.janzeer.sdk.rpc.TxView
import org.janzeer.sdk.rpc.WsEvent
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One node over the SDK's REST, JSON-RPC and WebSocket clients. Every call is wrapped by [Errors.map]. */
class SdkChainApi(override val profile: Profile, timeoutSec: Int) : ChainApi {
    private val http = OkHttpClient.Builder().callTimeout(timeoutSec.toLong(), TimeUnit.SECONDS).build()
    private val rpcHttp = OkHttpClient.Builder().callTimeout(70, TimeUnit.SECONDS).readTimeout(70, TimeUnit.SECONDS).build()
    private val client = JanzeerClient(profile.node, http)
    private val rpc by lazy { JanzeerRpc(profile.rpc ?: profile.node, rpcHttp) }

    override fun get(path: String, query: Map<String, Any?>): JsonElement? = Errors.wrap { client.get(path, query.ifEmpty { null }, null, true) }
    override fun post(path: String, body: JsonElement): JsonElement? = Errors.wrap { client.post(path, body) }
    override fun rpc(method: String, params: JsonElement?): JsonElement? = Errors.wrap { rpc.call(method, params) }

    override fun getAbsolute(url: String): JsonElement? = Errors.wrap {
        val res = try { http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute() }
        catch (e: IOException) { throw org.janzeer.sdk.client.NetworkException("Cannot reach $url: ${e.message}", e) }
        res.use { r ->
            if (r.code == 404) return@wrap null
            val text = r.body?.string().orEmpty()
            if (text.isEmpty()) return@wrap null
            try { JsonUtil.parse(text) } catch (e: Exception) { throw org.janzeer.sdk.client.NetworkException("$url returned non-JSON (${r.code})", e) }
        }
    }

    override fun subscribe(kind: String, params: JsonObject, onEvent: (JsonElement) -> Unit, onStatus: (String) -> Unit): AutoCloseable = Errors.wrap {
        val ws = JanzeerRpcWs.connect(profile.ws ?: profile.node, onEvent = { e ->
            when (e) {
                is WsEvent.Open -> onStatus("connected")
                is WsEvent.Closed -> onStatus("closed (${e.code ?: "-"} ${e.reason ?: ""})${if (e.willReconnect) ", reconnecting" else ""}")
                is WsEvent.Error -> onStatus("error: ${e.error.message}")
                is WsEvent.Resubscribed -> onStatus("resubscribed ${e.count}")
            }
        })
        val sub = when (kind) {
            "newBlocks" -> ws.subscribeNewBlocks(NewBlocksParams(params["fromHeight"]?.jsonPrimitive?.content?.toLong(), params["includeTransactions"]?.jsonPrimitive?.content == "true")) { onEvent(it.toJson()) }
            "addressActivity" -> ws.subscribeAddressActivity((params["addresses"] as JsonArray).map { it.jsonPrimitive.content }) { ev ->
                onEvent(buildJsonObject { put("blockHeight", ev.blockHeight); put("blockHash", ev.blockHash); put("transaction", ev.transaction.toJson()) })
            }
            else -> throw JzException(ExitCodes.UNEXPECTED, "subscribe", "unknown subscription '$kind'")
        }
        AutoCloseable { runCatching { sub.unsubscribe() }; runCatching { ws.close() } }
    }

    override fun waitForFinality(hash: String, timeoutMs: Long): JsonElement = Errors.wrap { Helpers.waitForFinality(rpc, hash, timeoutMs).toJson() }

    override fun close() { runCatching { client.close() }; runCatching { rpc.close() } }

    private fun BlockView.toJson(): JsonObject = buildJsonObject {
        put("type", type); put("height", height); put("hash", hash); put("previousHash", previousHash); put("timestamp", timestamp)
        put("producer", producer); put("epochIndex", epochIndex); put("transactionsCount", transactionsCount)
        transactions?.let { put("transactions", buildJsonArray { it.forEach { t -> add(t.toJson()) } }) }
    }

    private fun TxView.toJson(): JsonObject = buildJsonObject {
        put("hash", hash); put("type", type); put("status", status.wireName); put("timestamp", timestamp); put("fee", fee?.toPlainString()); put("nonce", nonce)
        put("senderAddress", senderAddress); put("blockHash", blockHash); put("blockHeight", blockHeight)
        put("amount", amount?.toPlainString()); put("recipientAddress", recipientAddress); put("data", data); put("validatorKey", validatorKey)
        put("op", op?.name); put("tokenId", tokenId); put("symbol", symbol); put("tokenAmount", tokenAmount?.toString()); put("recipient", recipient)
        put("timedOut", timedOut)
        receipt?.let { r -> put("receipt", buildJsonObject { put("successful", r.successful) }) }
    }
}
