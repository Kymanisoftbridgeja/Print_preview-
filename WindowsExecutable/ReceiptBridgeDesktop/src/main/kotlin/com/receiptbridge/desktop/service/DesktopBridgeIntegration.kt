package com.receiptbridge.desktop.service

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.receiptbridge.desktop.model.AppSettings
import com.receiptbridge.desktop.model.PrintJob
import io.ktor.server.application.ApplicationCall
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BridgeEventLevel {
    INFO,
    WARNING,
    ERROR
}

data class BridgeEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,
    val level: BridgeEventLevel,
    val message: String
)

class BridgeEventLog(
    private val maxEntries: Int = 30
) {
    private val _events = MutableStateFlow<List<BridgeEvent>>(emptyList())
    val events: StateFlow<List<BridgeEvent>> = _events.asStateFlow()

    fun record(
        source: String,
        level: BridgeEventLevel,
        message: String
    ) {
        val nextEntry = BridgeEvent(
            source = source,
            level = level,
            message = message
        )
        _events.value = (listOf(nextEntry) + _events.value).take(maxEntries)
    }
}

object BridgePayloadNormalizer {
    private val gson = GsonBuilder().create()

    fun normalizePayloadJson(requestBody: String): String {
        val trimmed = requestBody.trim()
        runCatching { PrintJobFactory.extractMetadata(trimmed) }.getOrNull()?.let {
            return trimmed
        }

        val root = try {
            JsonParser.parseString(trimmed)
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid JSON payload: ${error.message}")
        }

        if (!root.isJsonObject) {
            throw IllegalArgumentException("Invalid JSON payload: root must be a JSON object")
        }

        val json = root.asJsonObject
        val explicitType = json.getOptionalString("type")
            ?: json.getOptionalString("content_type")
            ?: json.getOptionalString("format")
        val text = json.getOptionalString("text")
            ?: json.getOptionalString("receipt_text")
            ?: json.getOptionalString("plain_text")
        val html = json.getOptionalString("html")
            ?: json.getOptionalString("receipt_html")
            ?: json.getOptionalString("receipt_markup")
        val structuredReceipt = json.getOptionalObject("structured_receipt")
        val renderedImageMeta = json.getOptionalObject("rendered_image_meta")
        val preferredRenderedImage = json.getOptionalString("rendered_image")
        val image = json.getOptionalString("image")
            ?: json.getOptionalString("receipt_image")
            ?: json.getOptionalString("image_base64")
            ?: preferredRenderedImage

        val contentType = when {
            structuredReceipt != null -> "odoo_structured"
            !preferredRenderedImage.isNullOrBlank() -> "receipt_image"
            explicitType != null && explicitType.contains("image", ignoreCase = true) && !image.isNullOrBlank() -> "receipt_image"
            explicitType != null && explicitType.contains("html", ignoreCase = true) && !html.isNullOrBlank() -> "receipt_html"
            explicitType != null && explicitType.contains("text", ignoreCase = true) && !text.isNullOrBlank() -> "receipt_text"
            !image.isNullOrBlank() -> "receipt_image"
            !text.isNullOrBlank() -> "receipt_text"
            !html.isNullOrBlank() -> "receipt_html"
            else -> throw IllegalArgumentException(
                "Invalid Odoo receipt payload: provide image, text, html, or a full content object"
            )
        }

        val normalized = JsonObject().apply {
            json.getOptionalString("printer_profile_id")?.let { addProperty("printer_profile_id", it) }
            json.getOptionalInt("copies")?.takeIf { it > 0 }?.let { addProperty("copies", it) }
            listOf("source", "print_type", "document_type", "order_name", "order_uid").forEach { fieldName ->
                json.getOptionalString(fieldName)?.let { addProperty(fieldName, it) }
            }
            add("content", JsonObject().apply {
                addProperty("type", contentType)
                when (contentType) {
                    "odoo_structured" -> {
                        add("structured_receipt", structuredReceipt)
                        image?.let { addProperty("image", it) }
                        renderedImageMeta?.let { add("rendered_image_meta", it.deepCopy()) }
                    }
                    "receipt_image" -> {
                        addProperty("image", image)
                        renderedImageMeta?.let { add("rendered_image_meta", it.deepCopy()) }
                    }
                    "receipt_html" -> addProperty("html", html)
                    else -> addProperty("text", text)
                }
            })
        }

        return gson.toJson(normalized)
    }
}

object LaunchPayloadParser {
    fun extractPayloadJson(arguments: List<String>): String? {
        if (arguments.isEmpty()) {
            return null
        }

        for (index in arguments.indices) {
            val argument = arguments[index]
            when {
                argument.startsWith("receiptbridge://", ignoreCase = true) -> {
                    return decodeReceiptBridgeUri(argument)
                }
                argument.startsWith("--payload-base64=") -> {
                    return decodeBase64Json(argument.substringAfter("="))
                }
                argument == "--payload-base64" -> {
                    return decodeBase64Json(arguments.getOrNull(index + 1))
                }
                argument.startsWith("--print-payload-json=") -> {
                    return argument.substringAfter("=").trim().ifBlank { null }
                }
                argument == "--print-payload-json" -> {
                    return arguments.getOrNull(index + 1)?.trim()?.ifBlank { null }
                }
            }
        }

        return null
    }

    private fun decodeReceiptBridgeUri(uri: String): String {
        val encodedPayload = uri.substringAfter("payload=", missingDelimiterValue = "")
            .substringBefore("&")
            .trim()
        if (encodedPayload.isBlank()) {
            throw IllegalArgumentException("receiptbridge launch payload is missing the payload query parameter")
        }

        val urlDecoded = URLDecoder.decode(encodedPayload, StandardCharsets.UTF_8)
        return decodeBase64Json(urlDecoded)
            ?: throw IllegalArgumentException("receiptbridge launch payload could not be decoded")
    }

    private fun decodeBase64Json(value: String?): String? {
        val normalized = value?.trim()?.ifBlank { null } ?: return null
        val base64Candidate = normalized.replace(' ', '+')
        val decodedBytes = runCatching {
            Base64.getDecoder().decode(base64Candidate)
        }.recoverCatching {
            Base64.getUrlDecoder().decode(base64Candidate)
        }.getOrElse {
            throw IllegalArgumentException("The launch payload is not valid Base64 JSON")
        }

        return decodedBytes.toString(StandardCharsets.UTF_8).trim().ifBlank { null }
    }
}

private fun JsonObject.getOptionalObject(fieldName: String): JsonObject? {
    val value = get(fieldName) ?: return null
    return if (value.isJsonObject) value.asJsonObject else null
}

fun AppSettings.allowedOriginRules(): List<String> {
    return odooAllowedOrigins
        .split(Regex("[,\\r\\n]+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

fun AppSettings.resolveAllowedOriginHeader(origin: String?): String? {
    if (origin.isNullOrBlank()) {
        return null
    }

    val normalizedOrigin = origin.trim().lowercase(Locale.US)
    val rules = allowedOriginRules()
    if (rules.isEmpty()) {
        return null
    }

    if (rules.any { it == "*" }) {
        return "*"
    }

    val matched = rules.any { rule ->
        val normalizedRule = rule.trim().lowercase(Locale.US)
        when {
            normalizedRule == normalizedOrigin -> true
            normalizedRule == "null" && normalizedOrigin == "null" -> true
            normalizedRule.contains('*') -> wildcardRuleMatches(normalizedRule, normalizedOrigin)
            else -> false
        }
    }

    return if (matched) origin else null
}

fun AppSettings.requiresBridgeToken(): Boolean {
    return !odooApiToken.isNullOrBlank()
}

fun AppSettings.matchesBridgeToken(candidate: String?): Boolean {
    val expected = odooApiToken?.trim()?.ifBlank { null } ?: return true
    return candidate?.trim() == expected
}

fun ApplicationCall.describeIntegrationClient(): String {
    val origin = request.headers["Origin"]?.trim().orEmpty()
    if (origin.isNotBlank()) {
        return origin
    }

    val userAgent = request.headers["User-Agent"]?.trim().orEmpty()
    if (userAgent.isNotBlank()) {
        return userAgent
    }

    return "local-client"
}

private fun wildcardRuleMatches(rule: String, origin: String): Boolean {
    val regex = Regex(
        "^" + Regex.escape(rule).replace("\\*", ".*") + "$",
        setOf(RegexOption.IGNORE_CASE)
    )
    return regex.matches(origin)
}

private fun JsonObject.getOptionalString(fieldName: String): String? {
    val value = get(fieldName) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
        return null
    }
    return value.asString.trim().ifBlank { null }
}

private fun JsonObject.getOptionalInt(fieldName: String): Int? {
    val value = get(fieldName) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
        return null
    }
    return runCatching { value.asInt }.getOrNull()
}
