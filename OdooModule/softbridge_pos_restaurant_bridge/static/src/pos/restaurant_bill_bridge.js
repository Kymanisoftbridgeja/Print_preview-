/** @odoo-module **/

import { loadAllImages } from "@point_of_sale/utils";
import { ControlButtons } from "@point_of_sale/app/screens/product_screen/control_buttons/control_buttons";
import { OrderReceipt } from "@point_of_sale/app/screens/receipt_screen/receipt/order_receipt";
import { patch } from "@web/core/utils/patch";
import { useService } from "@web/core/utils/hooks";

const DEFAULT_TIMEOUT_MS = 5000;
const DEFAULT_BRIDGE_URL = "http://127.0.0.1:9900";
const BILL_SOURCE = "odoo_pos_restaurant_bill_action";

patch(ControlButtons.prototype, {
    setup() {
        super.setup(...arguments);
        this.renderer = this.renderer || useService("renderer");
        this.notification = this.notification || useService("notification");
    },

    async clickPrintBill() {
        if (await this._trySendRestaurantBillToSoftbridge()) {
            return true;
        }
        return await super.clickPrintBill(...arguments);
    },

    async _trySendRestaurantBillToSoftbridge() {
        const bridgeConfig = getRestaurantBillBridgeConfig(this.pos?.config);
        if (!bridgeConfig.enabled) {
            return false;
        }

        const order = this.pos?.get_order?.();
        if (!order || !this.pos?.orderExportForPrinting) {
            return false;
        }

        const receiptData = {
            ...(this.pos.orderExportForPrinting(order) || {}),
            isBill: true,
            show_change: false,
        };
        try {
            const receiptRoot = await this.renderer.toHtml(OrderReceipt, {
                data: receiptData,
                formatCurrency: this.env.utils.formatCurrency,
            });
            receiptRoot?.classList?.add("pos-receipt-print", "p-3");
            try {
                await loadAllImages(receiptRoot);
            } catch (error) {
                console.warn("Softbridge restaurant bill images could not all be loaded.", error);
            }
            const receiptElement = resolveSoftbridgeReceiptElement(receiptRoot);
            const payload = await buildRestaurantBillPayload({
                order,
                receiptData,
                receiptElement,
                formatCurrency: this.env.utils.formatCurrency,
            });
            const endpoint = buildSoftbridgeEndpoint(bridgeConfig);
            await postSoftbridgePayload(endpoint, bridgeConfig, payload);
            this._notifySoftbridgeRestaurantBill(
                "Restaurant bill sent to Softbridge.",
                "success"
            );
            return true;
        } catch (error) {
            console.warn("Softbridge restaurant bill send failed.", error);
            this._notifySoftbridgeRestaurantBill(
                `Softbridge bill send failed: ${error.message || "Unknown error"}`,
                "warning"
            );
            return false;
        }
    },

    _notifySoftbridgeRestaurantBill(message, type = "info") {
        if (this.notification?.add) {
            this.notification.add(message, { type });
            return;
        }

        if (type === "danger") {
            console.error(message);
        } else {
            console.log(message);
        }
    },
});

function getRestaurantBillBridgeConfig(posConfig = {}) {
    return {
        enabled: Boolean(
            posConfig.softbridge_enabled &&
                posConfig.softbridge_restaurant_bill_action_enabled
        ),
        baseUrl: (posConfig.softbridge_bridge_url || DEFAULT_BRIDGE_URL).trim(),
        apiToken: (posConfig.softbridge_api_token || "").trim(),
        timeoutMs:
            Number(posConfig.softbridge_request_timeout_ms) || DEFAULT_TIMEOUT_MS,
    };
}

function buildSoftbridgeEndpoint(bridgeConfig) {
    const normalizedBaseUrl = bridgeConfig.baseUrl.replace(/\/+$/, "");
    return normalizedBaseUrl.endsWith("/odoo/receipt")
        ? normalizedBaseUrl
        : `${normalizedBaseUrl}/odoo/receipt`;
}

async function buildRestaurantBillPayload({
    order,
    receiptData,
    receiptElement,
    formatCurrency,
}) {
    const structuredReceipt = await buildRestaurantBillStructuredReceipt({
        order,
        receiptData,
        receiptElement,
        formatCurrency,
    });
    const renderedReceiptCapture = await maybeCaptureSoftbridgeRenderedImage(
        receiptElement
    );
    const payload = {
        order_name: structuredReceipt.order_name || receiptData?.name || "",
        order_uid: getSoftbridgeOrderKey(order),
        source: BILL_SOURCE,
        structured_receipt: structuredReceipt,
        capture_mode: renderedReceiptCapture ? "rendered_image" : "structured_bill",
    };

    if (renderedReceiptCapture?.dataUrl) {
        payload.type = "receipt_image";
        payload.image = renderedReceiptCapture.dataUrl;
        payload.rendered_image = renderedReceiptCapture.dataUrl;
        payload.rendered_image_meta = renderedReceiptCapture.meta;
    } else {
        payload.type = "odoo_structured";
    }

    return payload;
}

async function buildRestaurantBillStructuredReceipt({
    order,
    receiptData,
    receiptElement,
    formatCurrency,
}) {
    const header = receiptData?.headerData || {};
    const company = header.company || {};
    const documentReference = resolveRestaurantBillReference(order, receiptData);
    const documentTitle = "Pro forma receipt";
    const lines = buildSoftbridgeStructuredLinesFromExport(
        receiptData,
        formatCurrency
    );
    const summary = buildSoftbridgeStructuredSummaryFromExport(
        receiptData,
        formatCurrency
    );
    const footerLines = buildSoftbridgeStructuredFooterLines(receiptData, header, company);
    const logoImage = await extractSoftbridgeReceiptAsset(receiptElement, "logo");
    const qrImage = await extractSoftbridgeReceiptAsset(receiptElement, "qr");
    const domStructuredReceipt = extractRestaurantBillStructuredReceiptFromDom(
        receiptElement,
        {
            documentReference,
            documentTitle,
        }
    );
    const resolvedLines = selectBestSoftbridgeStructuredLines(
        lines,
        domStructuredReceipt?.lines || []
    );
    const resolvedSummary = hasMeaningfulSoftbridgeStructuredSummary(summary)
        ? summary
        : domStructuredReceipt?.summary || [];
    const resolvedFooterLines = mergeSoftbridgeStructuredTextLines(
        footerLines,
        domStructuredReceipt?.footerLines || []
    );
    const resolvedOrderName =
        String(receiptData?.name || "").trim() ||
        domStructuredReceipt?.orderName ||
        "";
    const resolvedDate =
        String(receiptData?.date || "").trim() ||
        domStructuredReceipt?.date ||
        "";
    const resolvedCashier =
        formatRestaurantBillCashier(header.cashier) ||
        domStructuredReceipt?.cashier ||
        "";

    return {
        company_name: logoImage ? "" : String(company.name || "").trim(),
        order_name: resolvedOrderName,
        date: resolvedDate,
        cashier: resolvedCashier,
        logo_image: logoImage,
        qr_image: qrImage,
        signature_label: "",
        document_reference: documentReference,
        document_title: documentTitle,
        lines: resolvedLines,
        summary: resolvedSummary,
        footer_lines: resolvedFooterLines,
    };
}

function resolveRestaurantBillReference(order, receiptData) {
    const candidates = [
        receiptData?.table,
        receiptData?.table_name,
        receiptData?.headerData?.table,
        order?.table?.name,
        order?.getTable?.()?.name,
    ];

    for (const candidate of candidates) {
        if (candidate === undefined || candidate === null) {
            continue;
        }
        if (typeof candidate === "object") {
            const objectValue =
                candidate.name ||
                candidate.label ||
                candidate.number ||
                candidate.id ||
                "";
            if (String(objectValue || "").trim()) {
                return String(objectValue).trim();
            }
            continue;
        }
        const normalized = String(candidate).trim();
        if (normalized) {
            return normalized;
        }
    }

    return "";
}

function formatRestaurantBillCashier(cashier) {
    const normalizedCashier = String(cashier || "").trim();
    return normalizedCashier ? `Served by: ${normalizedCashier}` : "";
}

function getSoftbridgeOrderKey(order) {
    return [
        order?.uid,
        order?.uuid,
        order?.name,
        order?.trackingNumber,
        order?.server_id,
    ].find(Boolean) || "";
}

function buildSoftbridgeStructuredLinesFromExport(receiptData, formatCurrency) {
    const lines = [];
    for (const line of receiptData?.orderlines || []) {
        const quantity = stringifySoftbridgeValue(
            line.qty ?? line.quantity ?? line.qty_str ?? ""
        );
        const name =
            line.productName ||
            line.product_name ||
            line.full_product_name ||
            line.name ||
            "";
        const amount = formatSoftbridgeMoney(
            line.price_display ??
                line.price ??
                line.price_with_tax ??
                line.price_subtotal_incl,
            formatCurrency
        );
        const unitPrice = formatSoftbridgeMoney(
            line.unit_price ?? line.price_unit ?? line.unitPrice,
            formatCurrency
        );
        const unitName =
            line.unit_name ||
            line.uom_name ||
            line.unit ||
            line.unitName ||
            line.unit_of_measure ||
            "";
        const sublineParts = [];
        if (unitPrice) {
            sublineParts.push(unitPrice);
        }
        if (unitName) {
            sublineParts.push(`/ ${unitName}`);
        }

        lines.push({
            qty: quantity,
            name: String(name || "").trim(),
            amount,
            subline: sublineParts.join(" ").trim(),
            note: String(line.customerNote || line.customer_note || "").trim(),
        });
    }
    return lines;
}

function buildSoftbridgeStructuredSummaryFromExport(receiptData, formatCurrency) {
    const summary = [];
    const taxTotals = receiptData?.taxTotals || {};

    // Collect all tax groups to combine with total
    const allTaxGroups = [];
    let subtotalAmount = "";
    for (const subtotal of taxTotals.subtotals || []) {
        subtotalAmount =
            subtotal.formatted_base_amount_currency ||
            subtotal.formatted_amount ||
            formatSoftbridgeMoney(subtotal.base_amount_currency, formatCurrency);
        for (const taxGroup of subtotal.tax_groups || []) {
            allTaxGroups.push({
                label: String(taxGroup.group_name || "Tax").trim(),
                amount:
                    taxGroup.formatted_tax_amount ||
                    formatSoftbridgeMoney(taxGroup.tax_amount_currency, formatCurrency),
            });
        }
    }

    // Subtotal with GCT on same line if present
    if (subtotalAmount) {
        const gctGroup = allTaxGroups.find((g) => /gct/i.test(g.label));
        if (gctGroup) {
            summary.push({
                label: "Subtotal",
                amount: subtotalAmount,
                right_label: gctGroup.label,
                right_amount: gctGroup.amount,
                emphasis: false,
            });
        } else {
            summary.push({
                label: "Subtotal",
                amount: subtotalAmount,
                emphasis: false,
            });
        }
    }

    // Total with remaining tax on same line
    const totalAmount =
        taxTotals.formatted_order_total ||
        formatSoftbridgeMoney(
            taxTotals.order_sign
                ? taxTotals.order_sign * taxTotals.order_total
                : taxTotals.order_total,
            formatCurrency
        );

    // Find non-GCT tax for right side of total
    const nonGctTax = allTaxGroups.find((g) => !/gct/i.test(g.label));
    summary.push({
        label: String(receiptData?.label_total || "Total").trim(),
        amount: totalAmount,
        right_label: nonGctTax?.label || "",
        right_amount: nonGctTax?.amount || "",
        emphasis: true,
    });

    if (receiptData?.show_rounding) {
        summary.push({
            label: String(receiptData.label_rounding || "Rounding").trim(),
            amount:
                taxTotals.formatted_rounding ||
                formatSoftbridgeMoney(
                    taxTotals.order_sign
                        ? taxTotals.order_sign * taxTotals.order_rounding
                        : taxTotals.order_rounding,
                    formatCurrency
                ),
            emphasis: false,
        });
    }

    // Payment method only - no amount for bills
    for (const paymentLine of receiptData?.paymentlines || []) {
        summary.push({
            label: String(paymentLine.name || "Payment").trim(),
            amount: "",
            emphasis: false,
        });
    }

    if (receiptData?.total_discount) {
        summary.push({
            label: String(receiptData.label_discounts || "Discounts").trim(),
            amount: formatSoftbridgeMoney(receiptData.total_discount, formatCurrency),
            emphasis: false,
        });
    }

    return summary.filter((row) => row.label);
}

function buildSoftbridgeStructuredFooterLines(receiptData, header, company) {
    const companyAddressLine = [
        company.street,
        company.street2,
        company.city,
        company.state_name,
        company.zip,
    ]
        .map((value) => String(value || "").trim())
        .filter(Boolean)
        .join(", ");
    const companyContactLine = [company.phone, company.mobile, company.email, company.website]
        .map((value) => String(value || "").trim())
        .filter(Boolean)
        .join("  ");

    return mergeSoftbridgeStructuredTextLines(
        [],
        [
            header.header,
            receiptData?.footer,
            companyAddressLine,
            companyContactLine,
        ].flatMap((value) => String(value || "").split(/\r?\n/))
    );
}

function hasMeaningfulSoftbridgeStructuredLines(lines) {
    return countMeaningfulSoftbridgeStructuredLines(lines) > 0;
}

function countMeaningfulSoftbridgeStructuredLines(lines) {
    if (!Array.isArray(lines)) {
        return 0;
    }
    return lines.filter((line) => {
        const name = String(line?.name || "").trim();
        const amount = String(line?.amount || "").trim();
        const qty = String(line?.qty || "").trim();
        return name || amount || qty;
    }).length;
}

function selectBestSoftbridgeStructuredLines(exportLines = [], domLines = []) {
    const exportCount = countMeaningfulSoftbridgeStructuredLines(exportLines);
    const domCount = countMeaningfulSoftbridgeStructuredLines(domLines);
    if (!exportCount) {
        return domLines || [];
    }
    if (domCount > exportCount) {
        return domLines;
    }
    return exportLines;
}

function hasMeaningfulSoftbridgeStructuredSummary(summary) {
    return Array.isArray(summary) && summary.some((row) => {
        const label = String(row?.label || "").trim();
        const amount = String(row?.amount || "").trim();
        return label && amount;
    });
}

function mergeSoftbridgeStructuredTextLines(primaryLines = [], fallbackLines = []) {
    const mergedLines = [];
    [...primaryLines, ...fallbackLines]
        .flatMap((value) => String(value || "").split(/\r?\n/))
        .map((value) => normalizeSoftbridgeReceiptText(value))
        .filter(Boolean)
        .forEach((value) => {
            if (!mergedLines.some((existing) => existing.toLowerCase() === value.toLowerCase())) {
                mergedLines.push(value);
            }
        });
    return mergedLines;
}

function extractRestaurantBillStructuredReceiptFromDom(
    receiptElement,
    { documentReference = "", documentTitle = "" } = {}
) {
    const textLines = extractSoftbridgeReceiptTextLines(receiptElement);
    if (!textLines.length) {
        return null;
    }

    const visibleLines = [...textLines];
    const orderNameIndex = visibleLines.findIndex((line) =>
        looksLikeSoftbridgeOrderNameLine(line)
    );
    const dateIndex = visibleLines.findIndex((line) => looksLikeSoftbridgeDateLine(line));
    const cashierIndex = visibleLines.findIndex((line) =>
        looksLikeSoftbridgeCashierLine(line)
    );
    const headerEndIndex = Math.max(orderNameIndex, dateIndex, cashierIndex);
    const bodyLines = visibleLines
        .slice(headerEndIndex >= 0 ? headerEndIndex + 1 : 0)
        .filter(Boolean);
    const contentLines = stripRestaurantBillHeadingLines(bodyLines, {
        documentReference,
        documentTitle,
    });

    let summaryStartIndex = -1;
    for (let index = 0; index < contentLines.length; index += 1) {
        if (looksLikeSoftbridgeSummaryLabel(contentLines[index])) {
            summaryStartIndex = index;
            break;
        }
    }

    let summaryEndIndex = contentLines.length;
    if (summaryStartIndex >= 0) {
        for (let index = summaryStartIndex; index < contentLines.length; index += 1) {
            const line = contentLines[index];
            if (index > summaryStartIndex && looksLikeSoftbridgeFooterLine(line)) {
                summaryEndIndex = index;
                break;
            }
        }
    }

    const itemTextLines = contentLines.slice(
        0,
        summaryStartIndex >= 0 ? summaryStartIndex : contentLines.length
    );
    const summaryTextLines =
        summaryStartIndex >= 0 ? contentLines.slice(summaryStartIndex, summaryEndIndex) : [];
    const footerLines =
        summaryStartIndex >= 0 ? contentLines.slice(summaryEndIndex).filter(Boolean) : [];

    return {
        orderName: orderNameIndex >= 0 ? visibleLines[orderNameIndex] : "",
        date: dateIndex >= 0 ? visibleLines[dateIndex] : "",
        cashier: cashierIndex >= 0 ? visibleLines[cashierIndex] : "",
        lines: buildSoftbridgeDomStructuredLines(itemTextLines),
        summary: buildSoftbridgeDomStructuredSummary(summaryTextLines),
        footerLines,
    };
}

function stripRestaurantBillHeadingLines(
    lines,
    { documentReference = "", documentTitle = "" } = {}
) {
    const remaining = [...lines];

    while (remaining.length) {
        const current = normalizeSoftbridgeReceiptText(remaining[0]).toLowerCase();
        if (!current) {
            remaining.shift();
            continue;
        }
        if (
            documentReference &&
            current === normalizeSoftbridgeReceiptText(documentReference).toLowerCase()
        ) {
            remaining.shift();
            continue;
        }
        if (
            documentTitle &&
            current === normalizeSoftbridgeReceiptText(documentTitle).toLowerCase()
        ) {
            remaining.shift();
            continue;
        }
        if (current === "pro forma" || current === "pro forma receipt") {
            remaining.shift();
            continue;
        }
        break;
    }

    return remaining;
}

function extractSoftbridgeReceiptTextLines(receiptElement) {
    const rawText = receiptElement?.innerText || receiptElement?.textContent || "";
    return String(rawText || "")
        .replace(/\r\n/g, "\n")
        .replace(/\r/g, "\n")
        .split("\n")
        .map((line) => normalizeSoftbridgeReceiptText(line).replace(/\s+/g, " ").trim())
        .filter(Boolean);
}

function buildSoftbridgeDomStructuredLines(textLines) {
    const structuredLines = [];
    for (let index = 0; index < textLines.length; index += 1) {
        const textLine = textLines[index];
        const parsedLine = parseSoftbridgeDomStructuredLine(textLine);
        if (parsedLine) {
            structuredLines.push(parsedLine);
            continue;
        }

        if (!looksLikeSoftbridgeQtyLine(textLine)) {
            continue;
        }

        const quantity = textLine.trim();
        let name = "";
        let amount = "";
        let subline = "";
        let note = "";

        while (index + 1 < textLines.length) {
            const candidate = textLines[index + 1];
            if (
                looksLikeSoftbridgeQtyLine(candidate) ||
                looksLikeSoftbridgeSummaryLabel(candidate) ||
                looksLikeSoftbridgeFooterLine(candidate)
            ) {
                break;
            }

            index += 1;
            if (!name && !looksLikeSoftbridgeAmountOnlyLine(candidate)) {
                name = candidate;
                continue;
            }

            if (!amount && looksLikeSoftbridgeAmountOnlyLine(candidate)) {
                amount = extractSoftbridgeTrailingAmountSafe(candidate);
                continue;
            }

            if (/^note\b[:\-]?\s*/i.test(candidate)) {
                const noteText = candidate.replace(/^note\b[:\-]?\s*/i, "").trim();
                note = note ? `${note} | ${noteText}` : noteText;
                continue;
            }

            subline = subline ? `${subline} | ${candidate}` : candidate;
        }

        if (name || amount) {
            structuredLines.push({
                qty: quantity,
                name,
                amount,
                subline,
                note,
            });
        }
    }

    return structuredLines.filter((line) => {
        const name = String(line?.name || "").trim();
        const amount = String(line?.amount || "").trim();
        const qty = String(line?.qty || "").trim();
        return name || amount || qty;
    });
}

function parseSoftbridgeDomStructuredLine(textLine) {
    const normalizedLine = normalizeSoftbridgeReceiptText(textLine)
        .replace(/\s+/g, " ")
        .trim();
    if (!normalizedLine) {
        return null;
    }
    if (
        looksLikeSoftbridgeOrderNameLine(normalizedLine) ||
        looksLikeSoftbridgeDateLine(normalizedLine) ||
        looksLikeSoftbridgeCashierLine(normalizedLine) ||
        parseSoftbridgeDomSummaryRow(normalizedLine)
    ) {
        return null;
    }

    const quantityMatch = normalizedLine.match(/^(\d+(?:\.\d+)?)\s+(.+)$/);
    if (!quantityMatch) {
        return null;
    }

    const trailingAmount = extractSoftbridgeTrailingAmountSafe(normalizedLine);
    if (!trailingAmount) {
        return null;
    }

    const contentWithoutAmount = normalizedLine
        .slice(0, normalizedLine.length - trailingAmount.length)
        .trim();
    const contentMatch = contentWithoutAmount.match(/^(\d+(?:\.\d+)?)\s+(.+)$/);
    if (!contentMatch) {
        return null;
    }

    return {
        qty: contentMatch[1].trim(),
        name: contentMatch[2].trim(),
        amount: trailingAmount,
        subline: "",
        note: "",
    };
}

function buildSoftbridgeDomStructuredSummary(textLines) {
    const summaryRows = [];
    for (let index = 0; index < textLines.length; index += 1) {
        const textLine = textLines[index];
        const parsedRow = parseSoftbridgeDomSummaryRow(textLine);
        if (parsedRow) {
            summaryRows.push(parsedRow);
            continue;
        }

        if (!looksLikeSoftbridgeSummaryLabel(textLine)) {
            continue;
        }

        const nextLine = textLines[index + 1] || "";
        const amount = looksLikeSoftbridgeAmountOnlyLine(nextLine)
            ? extractSoftbridgeTrailingAmountSafe(nextLine)
            : "";
        if (amount) {
            summaryRows.push({
                label: textLine.trim(),
                amount,
                emphasis: /^total\b/i.test(textLine.trim()),
            });
            index += 1;
        }
    }
    return summaryRows;
}

function parseSoftbridgeDomSummaryRow(textLine) {
    const normalizedLine = normalizeSoftbridgeReceiptText(textLine)
        .replace(/\s+/g, " ")
        .trim();
    if (!normalizedLine || !looksLikeSoftbridgeSummaryLabel(normalizedLine)) {
        return null;
    }

    const trailingAmount = extractSoftbridgeTrailingAmountSafe(normalizedLine);
    if (!trailingAmount) {
        return null;
    }

    const label = normalizedLine
        .slice(0, normalizedLine.length - trailingAmount.length)
        .trim();
    return {
        label,
        amount: trailingAmount,
        emphasis: /^total\b/i.test(label),
    };
}

function looksLikeSoftbridgeQtyLine(textLine) {
    return /^\d+(?:\.\d+)?$/.test(String(textLine || "").trim());
}

function looksLikeSoftbridgeAmountOnlyLine(textLine) {
    const normalizedLine = normalizeSoftbridgeReceiptText(textLine)
        .replace(/\s+/g, " ")
        .trim();
    if (!normalizedLine) {
        return false;
    }
    const amount = extractSoftbridgeTrailingAmountSafe(normalizedLine);
    return Boolean(amount) && normalizedLine === amount;
}

function looksLikeSoftbridgeSummaryLabel(textLine) {
    const normalizedLine = normalizeSoftbridgeReceiptText(textLine)
        .replace(/\s+/g, " ")
        .trim();
    if (!normalizedLine) {
        return false;
    }
    return /^(subtotal|total|tax\b|vat\b|gst\b|change\b|rounding\b|discounts?\b|cash\b|card\b|visa\b|master\s*card\b|mastercard\b|amex\b|debit\b|credit\b|payment\b|tender\b|balance\b|tip\b|service\b|ja\s+tax\b)/i.test(
        normalizedLine
    );
}

function looksLikeSoftbridgeFooterLine(textLine) {
    const normalizedLine = normalizeSoftbridgeReceiptText(textLine)
        .replace(/\s+/g, " ")
        .trim();
    if (!normalizedLine) {
        return false;
    }
    if (/^(bus|table|guest|gct|tel|phone|powered by|email|website)\b/i.test(normalizedLine)) {
        return true;
    }
    return normalizedLine.includes(",") || /\(\d{3}\)/.test(normalizedLine);
}

function looksLikeSoftbridgeOrderNameLine(textLine) {
    return /\b(invoice|ticket|receipt|order)\b/i.test(textLine);
}

function looksLikeSoftbridgeDateLine(textLine) {
    const normalizedLine = normalizeSoftbridgeReceiptText(textLine);
    return /(\b\d{1,2}[\/-]\d{1,2}[\/-]\d{2,4}\b|\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b)/i.test(
        normalizedLine
    ) && /(\b\d{1,2}:\d{2}\b|\bam\b|\bpm\b|\b\d{4}\b)/i.test(normalizedLine);
}

function looksLikeSoftbridgeCashierLine(textLine) {
    return /\b(served by|cashier|employee|server)\b/i.test(textLine);
}

function extractSoftbridgeTrailingAmountSafe(textLine) {
    const normalizedLine = normalizeSoftbridgeReceiptText(textLine)
        .replace(/\s+/g, " ")
        .trim();
    if (!normalizedLine) {
        return "";
    }

    const matches = Array.from(
        normalizedLine.matchAll(/\$?\s*-?\d[\d,]*(?:\.\d{1,2})?\s*\$?/g)
    );
    if (!matches.length) {
        return "";
    }

    const trailingMatch = matches[matches.length - 1];
    const matchIndex = trailingMatch.index ?? -1;
    if (matchIndex < 0) {
        return "";
    }

    const matchText = String(trailingMatch[0] || "").trim();
    const suffix = normalizedLine.slice(matchIndex + trailingMatch[0].length).trim();
    return suffix ? "" : matchText;
}

function stringifySoftbridgeValue(value) {
    if (value === undefined || value === null) {
        return "";
    }
    if (typeof value === "number") {
        return Number.isInteger(value) ? String(value) : String(value);
    }
    return String(value).trim();
}

function formatSoftbridgeMoney(value, formatCurrency) {
    if (value === undefined || value === null || value === "") {
        return "";
    }
    if (typeof value === "string") {
        return value.trim();
    }
    const numericValue = Number(value);
    if (Number.isNaN(numericValue)) {
        return String(value).trim();
    }
    try {
        return formatCurrency(numericValue);
    } catch {
        return String(value).trim();
    }
}

async function extractSoftbridgeReceiptAsset(receiptElement, kind) {
    if (!receiptElement) {
        return null;
    }

    const assetNode = findSoftbridgeReceiptAssetNode(receiptElement, kind);
    if (!assetNode) {
        return null;
    }

    return await convertSoftbridgeNodeToDataUrl(assetNode);
}

function findSoftbridgeReceiptAssetNode(receiptElement, kind) {
    const bounds = receiptElement.getBoundingClientRect();
    const receiptHeight = Math.max(1, bounds.height || receiptElement.scrollHeight || 0);
    const nodes = Array.from(receiptElement.querySelectorAll("img, canvas, svg"));
    const candidates = nodes
        .map((node) => {
            const nodeBounds = node.getBoundingClientRect?.();
            const width =
                nodeBounds?.width ||
                node.clientWidth ||
                node.width?.baseVal?.value ||
                node.width ||
                0;
            const height =
                nodeBounds?.height ||
                node.clientHeight ||
                node.height?.baseVal?.value ||
                node.height ||
                0;
            const top = nodeBounds?.top != null ? nodeBounds.top - bounds.top : 0;
            const area = width * height;
            const ratio = height > 0 ? width / height : 0;
            return { node, width, height, top, area, ratio };
        })
        .filter((candidate) => candidate.width >= 24 && candidate.height >= 24);

    if (!candidates.length) {
        return null;
    }

    if (kind === "logo") {
        return (
            candidates
                .filter((candidate) => candidate.top <= receiptHeight * 0.35)
                .sort((left, right) => right.area - left.area)[0]?.node || null
        );
    }

    return (
        candidates
            .filter((candidate) => {
                const isSquare = candidate.ratio >= 0.75 && candidate.ratio <= 1.25;
                return isSquare && candidate.top >= receiptHeight * 0.25;
            })
            .sort((left, right) => right.area - left.area)[0]?.node || null
    );
}

async function convertSoftbridgeNodeToDataUrl(node) {
    if (node instanceof HTMLCanvasElement) {
        return node.toDataURL("image/png");
    }

    if (node instanceof HTMLImageElement) {
        const currentSrc = node.currentSrc || node.src;
        if (!currentSrc) {
            return null;
        }
        return await convertSoftbridgeImageToDataUrl(currentSrc);
    }

    if (node instanceof SVGElement) {
        const svgMarkup = new XMLSerializer().serializeToString(node);
        return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svgMarkup)}`;
    }

    return null;
}

async function maybeCaptureSoftbridgeRenderedImage(receiptElement) {
    if (!receiptElement) {
        return null;
    }
    try {
        return await captureSoftbridgeReceiptImage(receiptElement);
    } catch (error) {
        console.warn("Softbridge restaurant bill image capture failed.", error);
        return null;
    }
}

async function captureSoftbridgeReceiptImage(receiptElement) {
    const targetElement = resolveSoftbridgeReceiptElement(receiptElement);
    if (!targetElement) {
        throw new Error("Receipt element was not available for bill image capture.");
    }

    const bounds = targetElement.getBoundingClientRect();
    const width = Math.max(
        1,
        Math.ceil(Math.max(targetElement.scrollWidth || 0, bounds.width || 0))
    );
    const height = Math.max(
        1,
        Math.ceil(Math.max(targetElement.scrollHeight || 0, bounds.height || 0))
    );
    const clonedElement = await cloneSoftbridgeReceiptForCapture(targetElement);
    const wrapper = document.createElement("div");
    wrapper.setAttribute("xmlns", "http://www.w3.org/1999/xhtml");
    wrapper.style.background = "#ffffff";
    wrapper.style.display = "inline-block";
    wrapper.style.width = `${width}px`;
    wrapper.style.height = `${height}px`;
    wrapper.style.minHeight = `${height}px`;
    wrapper.style.padding = "0";
    wrapper.style.margin = "0";
    wrapper.style.overflow = "visible";
    wrapper.appendChild(clonedElement);

    const svgMarkup = `
        <svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
            <foreignObject width="100%" height="100%">${new XMLSerializer().serializeToString(wrapper)}</foreignObject>
        </svg>
    `.trim();

    const svgUrl = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svgMarkup)}`;
    const renderedImage = await loadSoftbridgeImage(svgUrl);
    const canvas = document.createElement("canvas");
    const scale = Math.max(2, Math.ceil(window.devicePixelRatio || 1));
    canvas.width = Math.ceil(width * scale);
    canvas.height = Math.ceil(height * scale);
    const context = canvas.getContext("2d");
    if (!context) {
        throw new Error("Receipt image capture could not create a drawing context.");
    }

    context.scale(scale, scale);
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, width, height);
    context.drawImage(renderedImage, 0, 0, width, height);
    return {
        dataUrl: canvas.toDataURL("image/png"),
        meta: buildSoftbridgeRenderedImageMeta(targetElement, {
            cssWidthPx: width,
            cssHeightPx: height,
            canvasWidthPx: canvas.width,
            canvasHeightPx: canvas.height,
            captureScale: scale,
        }),
    };
}

function buildSoftbridgeRenderedImageMeta(targetElement, captureInfo) {
    const bounds = targetElement?.getBoundingClientRect?.() || {};
    const captureTarget = describeSoftbridgeReceiptElement(targetElement);
    return {
        capture_target: captureTarget,
        receipt_content_capture: captureTarget === "pos-receipt",
        css_width_px: Math.max(1, Math.ceil(captureInfo?.cssWidthPx || bounds.width || 0)),
        css_height_px: Math.max(1, Math.ceil(captureInfo?.cssHeightPx || bounds.height || 0)),
        scroll_width_px: Math.max(
            1,
            Math.ceil(targetElement?.scrollWidth || captureInfo?.cssWidthPx || bounds.width || 0)
        ),
        scroll_height_px: Math.max(
            1,
            Math.ceil(targetElement?.scrollHeight || captureInfo?.cssHeightPx || bounds.height || 0)
        ),
        canvas_width_px: Math.max(1, Math.ceil(captureInfo?.canvasWidthPx || 0)),
        canvas_height_px: Math.max(1, Math.ceil(captureInfo?.canvasHeightPx || 0)),
        capture_scale: Number(captureInfo?.captureScale || 1) || 1,
        device_pixel_ratio: Number(window.devicePixelRatio || 1) || 1,
    };
}

function describeSoftbridgeReceiptElement(targetElement) {
    if (!targetElement?.classList) {
        return targetElement?.tagName?.toLowerCase?.() || "unknown";
    }
    if (targetElement.classList.contains("pos-receipt")) {
        return "pos-receipt";
    }
    if (targetElement.classList.contains("pos-receipt-print")) {
        return "pos-receipt-print";
    }
    if (targetElement.matches?.(".pos-receipt-container .d-inline-block")) {
        return "receipt-inline-wrapper";
    }
    return targetElement.tagName?.toLowerCase?.() || "unknown";
}

function resolveSoftbridgeReceiptElement(receiptElement) {
    if (!receiptElement) {
        return null;
    }
    if (receiptElement.classList?.contains("pos-receipt")) {
        return receiptElement;
    }
    const nestedReceipt = receiptElement.querySelector?.(".pos-receipt");
    if (nestedReceipt) {
        return nestedReceipt;
    }
    const closestReceipt = receiptElement.closest?.(".pos-receipt");
    if (closestReceipt) {
        return closestReceipt;
    }
    const visualRootSelector = ".pos-receipt-print, .pos-receipt-container .d-inline-block";
    if (receiptElement.matches?.(visualRootSelector)) {
        return receiptElement;
    }
    const closestVisualRoot = receiptElement.closest?.(visualRootSelector);
    if (closestVisualRoot) {
        return closestVisualRoot;
    }
    return receiptElement;
}

async function cloneSoftbridgeReceiptForCapture(sourceElement) {
    const clonedElement = sourceElement.cloneNode(true);
    copySoftbridgeComputedStyles(sourceElement, clonedElement);
    syncSoftbridgeFormValues(sourceElement, clonedElement);
    copySoftbridgeCanvasContent(sourceElement, clonedElement);
    await inlineSoftbridgeImages(sourceElement, clonedElement);
    await inlineSoftbridgeBackgroundImages(sourceElement, clonedElement);
    clonedElement.style.overflow = "visible";
    clonedElement.style.maxHeight = "none";
    return clonedElement;
}

function copySoftbridgeComputedStyles(sourceNode, clonedNode) {
    if (!(sourceNode instanceof Element) || !(clonedNode instanceof Element)) {
        return;
    }

    const computedStyle = window.getComputedStyle(sourceNode);
    for (const propertyName of computedStyle) {
        clonedNode.style.setProperty(
            propertyName,
            computedStyle.getPropertyValue(propertyName),
            computedStyle.getPropertyPriority(propertyName)
        );
    }

    const sourceChildren = Array.from(sourceNode.children);
    const clonedChildren = Array.from(clonedNode.children);
    for (let index = 0; index < sourceChildren.length; index += 1) {
        copySoftbridgeComputedStyles(sourceChildren[index], clonedChildren[index]);
    }
}

function syncSoftbridgeFormValues(sourceNode, clonedNode) {
    const sourceFields = sourceNode.querySelectorAll?.("input, textarea, select") || [];
    const clonedFields = clonedNode.querySelectorAll?.("input, textarea, select") || [];
    Array.from(sourceFields).forEach((sourceField, index) => {
        const clonedField = clonedFields[index];
        if (!clonedField) {
            return;
        }

        if ("value" in sourceField) {
            clonedField.setAttribute("value", sourceField.value ?? "");
        }
        if (sourceField instanceof HTMLTextAreaElement) {
            clonedField.textContent = sourceField.value ?? "";
        }
        if (sourceField instanceof HTMLInputElement && sourceField.checked) {
            clonedField.setAttribute("checked", "checked");
        }
        if (sourceField instanceof HTMLSelectElement && clonedField instanceof HTMLSelectElement) {
            Array.from(clonedField.options).forEach((option, optionIndex) => {
                option.selected = Boolean(sourceField.options[optionIndex]?.selected);
            });
        }
    });
}

function copySoftbridgeCanvasContent(sourceNode, clonedNode) {
    const sourceCanvases = sourceNode.querySelectorAll?.("canvas") || [];
    const clonedCanvases = clonedNode.querySelectorAll?.("canvas") || [];
    Array.from(sourceCanvases).forEach((sourceCanvas, index) => {
        const clonedCanvas = clonedCanvases[index];
        if (!(sourceCanvas instanceof HTMLCanvasElement) || !(clonedCanvas instanceof HTMLCanvasElement)) {
            return;
        }

        try {
            const image = document.createElement("img");
            image.setAttribute("src", sourceCanvas.toDataURL("image/png"));
            image.setAttribute("width", String(sourceCanvas.width));
            image.setAttribute("height", String(sourceCanvas.height));
            image.setAttribute("style", clonedCanvas.getAttribute("style") || "");
            clonedCanvas.replaceWith(image);
        } catch {
            // Keep the cloned canvas if image conversion fails.
        }
    });
}

async function inlineSoftbridgeImages(sourceNode, clonedNode) {
    const sourceImages = sourceNode.querySelectorAll?.("img") || [];
    const clonedImages = clonedNode.querySelectorAll?.("img") || [];
    const tasks = Array.from(sourceImages).map(async (sourceImage, index) => {
        const clonedImage = clonedImages[index];
        if (!clonedImage) {
            return;
        }

        const currentSrc = sourceImage.currentSrc || sourceImage.src;
        if (!currentSrc) {
            return;
        }

        try {
            const dataUrl = await convertSoftbridgeImageToDataUrl(currentSrc);
            clonedImage.setAttribute("src", dataUrl);
        } catch {
            clonedImage.setAttribute("src", currentSrc);
        }
    });
    await Promise.all(tasks);
}

async function inlineSoftbridgeBackgroundImages(sourceNode, clonedNode) {
    const sourceElements = [sourceNode, ...(sourceNode.querySelectorAll?.("*") || [])];
    const clonedElements = [clonedNode, ...(clonedNode.querySelectorAll?.("*") || [])];
    const tasks = sourceElements.map(async (sourceElement, index) => {
        const clonedElement = clonedElements[index];
        if (!(sourceElement instanceof Element) || !(clonedElement instanceof Element)) {
            return;
        }

        const computedStyle = window.getComputedStyle(sourceElement);
        const backgroundImage = computedStyle.getPropertyValue("background-image");
        if (!backgroundImage || backgroundImage === "none") {
            return;
        }

        const urls = Array.from(
            backgroundImage.matchAll(/url\((['"]?)(.*?)\1\)/g),
            (match) => match[2]
        ).filter(Boolean);
        if (!urls.length) {
            return;
        }

        let inlinedBackgroundImage = backgroundImage;
        for (const url of urls) {
            try {
                const dataUrl = await convertSoftbridgeImageToDataUrl(url);
                inlinedBackgroundImage = inlinedBackgroundImage.replace(url, dataUrl);
            } catch {
                // Keep the original URL if it cannot be inlined.
            }
        }
        clonedElement.style.setProperty("background-image", inlinedBackgroundImage);
    });
    await Promise.all(tasks);
}

async function convertSoftbridgeImageToDataUrl(url) {
    if (url.startsWith("data:")) {
        return url;
    }

    const response = await fetch(url, { mode: "cors", credentials: "include" });
    if (!response.ok) {
        throw new Error(`Image fetch failed with status ${response.status}`);
    }
    const blob = await response.blob();
    return await new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result);
        reader.onerror = () => reject(new Error("Image conversion failed."));
        reader.readAsDataURL(blob);
    });
}

async function loadSoftbridgeImage(source) {
    return await new Promise((resolve, reject) => {
        const image = new Image();
        image.decoding = "sync";
        image.onload = () => resolve(image);
        image.onerror = () => reject(new Error("Receipt image rendering failed."));
        image.src = source;
    });
}

function normalizeSoftbridgeReceiptText(value) {
    return String(value ?? "")
        .replace(/[\u00A0\u1680\u2000-\u200A\u202F\u205F\u3000]/g, " ")
        .replace(/\r\n/g, "\n")
        .replace(/\r/g, "\n")
        .replace(/[ \t]+\n/g, "\n")
        .replace(/\n{3,}/g, "\n\n")
        .trim();
}

async function postSoftbridgePayload(endpoint, bridgeConfig, payload) {
    const abortController = new AbortController();
    const timeoutHandle = window.setTimeout(() => {
        abortController.abort();
    }, bridgeConfig.timeoutMs);

    try {
        const headers = {
            "Content-Type": "application/json",
        };
        if (bridgeConfig.apiToken) {
            headers["X-ReceiptBridge-Token"] = bridgeConfig.apiToken;
        }

        const response = await fetch(endpoint, {
            method: "POST",
            headers,
            mode: "cors",
            credentials: "omit",
            body: JSON.stringify(payload),
            signal: abortController.signal,
        });

        const rawBody = await response.text();
        const parsedBody = rawBody ? safeParseSoftbridgeJson(rawBody) : {};
        if (!response.ok) {
            const message =
                parsedBody?.message ||
                parsedBody?.error ||
                `Softbridge request failed with status ${response.status}.`;
            throw new Error(message);
        }

        return parsedBody;
    } catch (error) {
        if (error?.name === "AbortError") {
            throw new Error(
                `Softbridge request timed out after ${bridgeConfig.timeoutMs} ms.`
            );
        }
        throw error;
    } finally {
        window.clearTimeout(timeoutHandle);
    }
}

function safeParseSoftbridgeJson(rawBody) {
    try {
        return JSON.parse(rawBody);
    } catch {
        return { raw: rawBody };
    }
}
