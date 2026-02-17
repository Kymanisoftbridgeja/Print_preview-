package com.receiptbridge.escpos

import com.google.gson.annotations.SerializedName

data class PrintPayload(
    @SerializedName("printer_profile_id") val printerProfileId: String?,
    @SerializedName("paper_size") val paperSize: String?,
    @SerializedName("copies") val copies: Int = 1,
    @SerializedName("content") val content: PrintContent
)

data class PrintContent(
    @SerializedName("type") val type: String, // "escpos_blocks"
    @SerializedName("blocks") val blocks: List<PrintBlock>
)

data class PrintBlock(
    @SerializedName("cmd") val cmd: String,
    @SerializedName("value") val value: Any?,
    @SerializedName("left") val left: String?,
    @SerializedName("right") val right: String?
)
