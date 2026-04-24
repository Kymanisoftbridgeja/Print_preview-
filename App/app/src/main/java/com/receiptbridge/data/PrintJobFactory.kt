package com.receiptbridge.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser

object PrintJobFactory {
    fun createFromPayloadJson(payloadJson: String): PrintJob {
        val metadata = extractMetadata(payloadJson)
        return PrintJob(
            printerProfileId = metadata.printerProfileId,
            payloadJson = payloadJson,
            copies = metadata.copies
        )
    }

    fun extractMetadata(payloadJson: String): PrintJobMetadata {
        val root = try {
            JsonParser.parseString(payloadJson)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid JSON payload: ${e.message}")
        }

        if (!root.isJsonObject) {
            throw IllegalArgumentException("Invalid JSON payload: root must be a JSON object")
        }

        val json = root.asJsonObject
        validateRequiredStructure(json)

        return PrintJobMetadata(
            printerProfileId = json.getOptionalString("printer_profile_id"),
            copies = json.getOptionalInt("copies")?.takeIf { it > 0 } ?: 1
        )
    }

    private fun validateRequiredStructure(json: JsonObject) {
        val content = json.get("content")
        if (content == null || !content.isJsonObject) {
            throw IllegalArgumentException("Invalid JSON payload: missing content object")
        }

        val blocks = content.asJsonObject.get("blocks")
        if (blocks == null || !blocks.isJsonArray) {
            throw IllegalArgumentException("Invalid JSON payload: missing content.blocks array")
        }
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
}

data class PrintJobMetadata(
    val printerProfileId: String?,
    val copies: Int
)
