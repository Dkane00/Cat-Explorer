package net.kanenet.catexplorer.rig

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Thrown when FLRig can't be reached or returns a fault. */
class RpcException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Minimal XML-RPC client — just enough for FLRig (HTTP POST to /RPC2). */
class XmlRpcClient(private val host: String, private val port: Int, private val timeoutMs: Int = 5000) {

    private val url = URL("http", host, port, "/RPC2")

    /** Calls one method. Returns String, Int, Double, Boolean, List<Any?>, Map<String, Any?> or null. */
    fun call(method: String, vararg params: Any): Any? {
        val body = buildRequest(method, params.toList())
        val response = post(body)
        return parseResponse(response)
    }

    /** Calls several methods in one HTTP round trip. Each entry is the value, or an [RpcException] for a fault. */
    fun multicall(calls: List<Pair<String, List<Any>>>): List<Any?> {
        val arr = calls.map { (m, p) -> mapOf("methodName" to m, "params" to p) }
        val result = call("system.multicall", arr) as? List<*> ?: throw RpcException("Bad multicall reply")
        return result.map { entry ->
            when (entry) {
                is List<*> -> entry.firstOrNull()
                is Map<*, *> -> RpcException(entry["faultString"]?.toString() ?: "fault")
                else -> entry
            }
        }
    }

    private fun post(body: String): ByteArray {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) throw RpcException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } catch (e: RpcException) {
            throw e
        } catch (e: Exception) {
            throw RpcException(e.message ?: e.javaClass.simpleName, e)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun buildRequest(method: String, params: List<Any?>): String = buildString {
            append("<?xml version=\"1.0\"?><methodCall><methodName>")
            append(escape(method))
            append("</methodName><params>")
            params.forEach { append("<param>"); writeValue(it); append("</param>") }
            append("</params></methodCall>")
        }

        private fun StringBuilder.writeValue(v: Any?) {
            append("<value>")
            when (v) {
                is Int -> append("<i4>$v</i4>")
                is Long -> append("<i4>$v</i4>")
                is Double -> append("<double>$v</double>")
                is Boolean -> append("<boolean>${if (v) 1 else 0}</boolean>")
                is List<*> -> { append("<array><data>"); v.forEach { writeValue(it) }; append("</data></array>") }
                is Map<*, *> -> {
                    append("<struct>")
                    v.forEach { (k, x) -> append("<member><name>${escape(k.toString())}</name>"); writeValue(x); append("</member>") }
                    append("</struct>")
                }
                else -> append(escape(v?.toString() ?: ""))
            }
            append("</value>")
        }

        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun parseResponse(xml: ByteArray): Any? {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(ByteArrayInputStream(xml))
            val root = doc.documentElement
            val fault = root.children("fault").firstOrNull()
            if (fault != null) {
                val v = parseValue(fault.children("value").first()) as? Map<*, *>
                throw RpcException(v?.get("faultString")?.toString() ?: "XML-RPC fault")
            }
            val param = root.children("params").firstOrNull()?.children("param")?.firstOrNull()
                ?: return null
            return parseValue(param.children("value").first())
        }

        private fun parseValue(value: Element): Any? {
            val typed = value.childNodes.let { nl -> (0 until nl.length).map { nl.item(it) }.firstOrNull { it.nodeType == Node.ELEMENT_NODE } as Element? }
                ?: return value.textContent   // untyped value == string
            return when (typed.tagName) {
                "string" -> typed.textContent
                "i4", "int", "i8" -> typed.textContent.trim().toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
                "double" -> typed.textContent.trim().toDoubleOrNull()
                "boolean" -> typed.textContent.trim() == "1"
                "array" -> typed.children("data").firstOrNull()?.children("value")?.map { parseValue(it) } ?: emptyList<Any?>()
                "struct" -> typed.children("member").associate { m ->
                    m.children("name").first().textContent to parseValue(m.children("value").first())
                }
                else -> typed.textContent
            }
        }

        private fun Element.children(tag: String): List<Element> {
            val out = ArrayList<Element>()
            for (i in 0 until childNodes.length) {
                val n = childNodes.item(i)
                if (n is Element && n.tagName == tag) out.add(n)
            }
            return out
        }
    }
}
