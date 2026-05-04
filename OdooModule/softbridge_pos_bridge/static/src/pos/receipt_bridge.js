/** @odoo-module **/

import { ReceiptScreen } from "@point_of_sale/app/screens/receipt_screen/receipt_screen";
import { patch } from "@web/core/utils/patch";
import { useState, onMounted, onPatched } from "@odoo/owl";
import { useService } from "@web/core/utils/hooks";

const AUTO_SEND_STATES = new Map();
const DEFAULT_TIMEOUT_MS = 5000;
const DEFAULT_BRIDGE_URL = "http://127.0.0.1:9900";
const RECEIPT_SELECTORS = [
    ".pos-receipt-container .d-inline-block",
    ".pos-receipt-print",
    ".receipt-screen .pos-receipt",
    ".pos-receipt-container .pos-receipt",
    ".pos-receipt",
];

patch(ReceiptScreen.prototype, {
    setup() {
        super.setup(...arguments);
        this.notification = this.notification || useService("notification");
        this.sendToSoftbridge = useState({ status: "idle", error: null, acceptedAs: null });

        onMounted(() => {
            void this._maybeAutoSendReceiptToSoftbridge();
        });
        onPatched(() => {
            void this._maybeAutoSendReceiptToSoftbridge();
        });
    },

    async _callSendToSoftbridge(options) {
        if (this.sendToSoftbridge.status === "loading") {
            return;
        }
        this.sendToSoftbridge.status = "loading";
        this.sendToSoftbridge.error = null;
        this.sendToSoftbridge.acceptedAs = null;
        try {
            const res = await this._sendReceiptToSoftbridge(options);
            this.sendToSoftbridge.status = "success";
            return res;
        } catch (error) {
            this.sendToSoftbridge.status = "error";
            this.sendToSoftbridge.error = error;
            throw error;
        }
    },

    async onSendToSoftbridgeClick() {
        try {
            await this._callSendToSoftbridge({ manual: true });
        } catch (error) {
            // The error is already handled and notified in _sendReceiptToSoftbridge.
            // We just catch it here so it doesn't cause unhandled promise rejections.
        }
    },

    async _maybeAutoSendReceiptToSoftbridge() {
        const bridgeConfig = this._getSoftbridgeConfig();
        if (!bridgeConfig.enabled || !bridgeConfig.autoSendReceipt) {
            return;
        }

        const orderKey = this._getSoftbridgeOrderKey();
        if (!orderKey || AUTO_SEND_STATES.has(orderKey)) {
            return;
        }

        AUTO_SEND_STATES.set(orderKey, "pending");
        try {
            await this._callSendToSoftbridge({ manual: false });
            AUTO_SEND_STATES.set(orderKey, "success");
        } catch (error) {
            AUTO_SEND_STATES.set(orderKey, "failed");
            this._notifySoftbridge(
                `Softbridge auto-send failed: ${error.message || "Unknown error"}`,
                "warning"
            );
        }
    },

    async _sendReceiptToSoftbridge({ manual = false } = {}) {
        const bridgeConfig = this._getSoftbridgeConfig();
        if (!bridgeConfig.enabled) {
            const error = new Error("Softbridge is disabled for this POS configuration.");
            if (manual) {
                this._notifySoftbridge(error.message, "warning");
            }
            throw error;
        }

        const receiptElement = await this._waitForSoftbridgeReceiptElement();
        if (!receiptElement && ["html", "image"].includes(bridgeConfig.payloadMode)) {
            const error = new Error("Receipt element was not available on the receipt screen.");
            if (manual) {
                this._notifySoftbridge(error.message, "danger");
            }
            throw error;
        }

        const endpoint = this._buildSoftbridgeEndpoint(bridgeConfig);
        const payload = await this._buildSoftbridgePayload(bridgeConfig, receiptElement);
        const responseBody = await this._postSoftbridgePayload(endpoint, bridgeConfig, payload);
        const acceptedAs = responseBody?.accepted_as || responseBody?.content_type || payload.type;
        this.sendToSoftbridge.acceptedAs = acceptedAs;

        if (manual) {
            const message = acceptedAs === "receipt_image"
                ? "Receipt sent to Softbridge as a rendered receipt image."
                : `Receipt sent to Softbridge as ${acceptedAs}.`;
            this._notifySoftbridge(message, acceptedAs === "receipt_image" ? "success" : "warning");
        } else {
            console.info("Softbridge receipt queued.", responseBody);
        }

        return responseBody;
    },

    _getSoftbridgeConfig() {
        const config = this.pos.config || {};
        return {
            enabled: Boolean(config.softbridge_enabled),
            baseUrl: (config.softbridge_bridge_url || DEFAULT_BRIDGE_URL).trim(),
            apiToken: (config.softbridge_api_token || "").trim(),
            autoSendReceipt: Boolean(config.softbridge_auto_send_receipt),
            manualButton: Boolean(config.softbridge_manual_button),
            payloadMode: config.softbridge_payload_mode || "text",
            timeoutMs: Number(config.softbridge_request_timeout_ms) || DEFAULT_TIMEOUT_MS,
        };
    },

    _getSoftbridgeOrderKey() {
        const order = this.currentOrder;
        return [
            order?.uid,
            order?.uuid,
            order?.name,
            order?.trackingNumber,
            order?.server_id,
        ].find(Boolean) || null;
    },

    _buildSoftbridgeEndpoint(bridgeConfig) {
        const normalizedBaseUrl = bridgeConfig.baseUrl.replace(/\/+$/, "");
        return normalizedBaseUrl.endsWith("/odoo/receipt")
            ? normalizedBaseUrl
            : `${normalizedBaseUrl}/odoo/receipt`;
    },

    async _buildSoftbridgePayload(bridgeConfig, receiptElement) {
        const order = this.currentOrder;
        const receiptData = this._getSoftbridgeReceiptData();
        const resolvedReceiptElement = this._resolveSoftbridgeReceiptElement(receiptElement);
        const renderedImage = await this._maybeCaptureSoftbridgeRenderedImage(resolvedReceiptElement);
        const structuredReceipt = await this._buildSoftbridgeStructuredReceipt(
            receiptData,
            resolvedReceiptElement
        );
        const payload = {
            order_name: order?.name || "",
            order_uid: this._getSoftbridgeOrderKey() || "",
            source: "odoo_pos_receipt_screen",
            structured_receipt: structuredReceipt,
        };

        if (renderedImage) {
            payload.type = "receipt_image";
            payload.image = renderedImage;
            payload.rendered_image = renderedImage;
        } else if (bridgeConfig.payloadMode === "html") {
            const receiptHtml = (resolvedReceiptElement?.innerHTML || "").trim();
            payload.type = "receipt_html";
            payload.html = receiptHtml;
        } else {
            const receiptText = this._buildSoftbridgeTextReceipt(receiptData, resolvedReceiptElement);
            payload.type = "receipt_text";
            payload.text = receiptText;
        }

        payload.capture_mode = renderedImage ? "rendered_image" : `fallback_${bridgeConfig.payloadMode}`;
        return payload;
    },

    async _buildSoftbridgeStructuredReceipt(receiptData, receiptElement) {
        const header = receiptData?.headerData || {};
        const company = header.company || {};
        const lines = [];
        const summary = [];
        const footerLines = [];
        const logoImage = await this._extractSoftbridgeReceiptAsset(receiptElement, "logo");
        const qrImage = await this._extractSoftbridgeReceiptAsset(receiptElement, "qr");
        const signatureLabel = this._extractSoftbridgeSignatureLabel(receiptElement);

        for (const line of receiptData?.orderlines || []) {
            const quantity = this._stringifySoftbridgeValue(
                line.qty ?? line.quantity ?? line.qty_str ?? ""
            );
            const name =
                line.productName ||
                line.product_name ||
                line.full_product_name ||
                line.name ||
                "";
            const amount = this._formatSoftbridgeMoney(
                line.price_display ??
                    line.price ??
                    line.price_with_tax ??
                    line.price_subtotal_incl
            );
            const unitPrice = this._formatSoftbridgeMoney(
                line.unit_price ??
                    line.price_unit ??
                    line.unitPrice
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

        const taxTotals = receiptData?.taxTotals || {};
        for (const subtotal of taxTotals.subtotals || []) {
            const subtotalAmount =
                subtotal.formatted_base_amount_currency ||
                subtotal.formatted_amount ||
                this._formatSoftbridgeMoney(subtotal.base_amount_currency);
            summary.push({
                label: String(subtotal.name || "Subtotal").trim(),
                amount: subtotalAmount,
                emphasis: false,
            });
            for (const taxGroup of subtotal.tax_groups || []) {
                summary.push({
                    label: String(taxGroup.group_name || "Tax").trim(),
                    amount:
                        taxGroup.formatted_tax_amount ||
                        this._formatSoftbridgeMoney(taxGroup.tax_amount_currency),
                    emphasis: false,
                });
            }
        }

        summary.push({
            label: String(receiptData?.label_total || "Total").trim(),
            amount:
                taxTotals.formatted_order_total ||
                this._formatSoftbridgeMoney(
                    taxTotals.order_sign
                        ? taxTotals.order_sign * taxTotals.order_total
                        : taxTotals.order_total
                ),
            emphasis: true,
        });

        if (receiptData?.show_rounding) {
            summary.push({
                label: String(receiptData.label_rounding || "Rounding").trim(),
                amount:
                    taxTotals.formatted_rounding ||
                    this._formatSoftbridgeMoney(
                        taxTotals.order_sign
                            ? taxTotals.order_sign * taxTotals.order_rounding
                            : taxTotals.order_rounding
                    ),
                emphasis: false,
            });
        }

        if (receiptData?.show_change) {
            summary.push({
                label: String(receiptData.label_change || "Change").trim(),
                amount: this._formatSoftbridgeMoney(receiptData.order_change),
                emphasis: false,
            });
        }

        for (const paymentLine of receiptData?.paymentlines || []) {
            summary.push({
                label: String(paymentLine.name || "Payment").trim(),
                amount:
                    paymentLine.formatted_amount ||
                    this._formatSoftbridgeMoney(paymentLine.amount),
                emphasis: false,
            });
        }

        if (receiptData?.total_discount) {
            summary.push({
                label: String(receiptData.label_discounts || "Discounts").trim(),
                amount: this._formatSoftbridgeMoney(receiptData.total_discount),
                emphasis: false,
            });
        }

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

        [
            header.header,
            receiptData?.footer,
            company.name,
            companyAddressLine,
            companyContactLine,
        ]
            .flatMap((value) => String(value || "").split(/\r?\n/))
            .map((value) => this._normalizeSoftbridgeReceiptText(value))
            .filter(Boolean)
            .forEach((value) => {
                if (!footerLines.includes(value)) {
                    footerLines.push(value);
                }
            });

        return {
            company_name: String(company.name || "").trim(),
            order_name: String(receiptData?.name || "").trim(),
            date: String(receiptData?.date || "").trim(),
            cashier: String(header.cashier || "").trim(),
            logo_image: logoImage,
            qr_image: qrImage,
            signature_label: signatureLabel,
            lines,
            summary,
            footer_lines: footerLines,
        };
    },

    _stringifySoftbridgeValue(value) {
        if (value === undefined || value === null) {
            return "";
        }
        if (typeof value === "number") {
            return Number.isInteger(value) ? String(value) : String(value);
        }
        return String(value).trim();
    },

    _formatSoftbridgeMoney(value) {
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
            return this.env.utils.formatCurrency(numericValue);
        } catch {
            return String(value).trim();
        }
    },

    async _extractSoftbridgeReceiptAsset(receiptElement, kind) {
        if (!receiptElement) {
            return null;
        }

        const assetNode = this._findSoftbridgeReceiptAssetNode(receiptElement, kind);
        if (!assetNode) {
            return null;
        }

        return await this._convertSoftbridgeNodeToDataUrl(assetNode);
    },

    _findSoftbridgeReceiptAssetNode(receiptElement, kind) {
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
            return candidates
                .filter((candidate) => candidate.top <= receiptHeight * 0.35)
                .sort((left, right) => right.area - left.area)[0]?.node || null;
        }

        return candidates
            .filter((candidate) => {
                const isSquare = candidate.ratio >= 0.75 && candidate.ratio <= 1.25;
                return isSquare && candidate.top >= receiptHeight * 0.25;
            })
            .sort((left, right) => right.area - left.area)[0]?.node || null;
    },

    async _convertSoftbridgeNodeToDataUrl(node) {
        if (node instanceof HTMLCanvasElement) {
            return node.toDataURL("image/png");
        }

        if (node instanceof HTMLImageElement) {
            const currentSrc = node.currentSrc || node.src;
            if (!currentSrc) {
                return null;
            }
            return await this._convertSoftbridgeImageToDataUrl(currentSrc);
        }

        if (node instanceof SVGElement) {
            const svgMarkup = new XMLSerializer().serializeToString(node);
            return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svgMarkup)}`;
        }

        return null;
    },

    _extractSoftbridgeSignatureLabel(receiptElement) {
        const receiptText = String(receiptElement?.innerText || receiptElement?.textContent || "");
        const match = receiptText.match(/customer signature/i);
        return match ? "Customer Signature" : "";
    },

    async _maybeCaptureSoftbridgeRenderedImage(receiptElement) {
        if (!receiptElement) {
            return null;
        }
        try {
            return await this._captureSoftbridgeReceiptImage(receiptElement);
        } catch (error) {
            console.warn("Softbridge rendered receipt capture failed.", error);
            return null;
        }
    },

    async _captureSoftbridgeReceiptImage(receiptElement) {
        const targetElement = this._resolveSoftbridgeReceiptElement(receiptElement);
        if (!targetElement) {
            throw new Error("Receipt element was not available for image capture.");
        }

        const bounds = targetElement.getBoundingClientRect();
        const width = Math.max(1, Math.ceil(Math.max(targetElement.scrollWidth || 0, bounds.width || 0)));
        const height = Math.max(1, Math.ceil(Math.max(targetElement.scrollHeight || 0, bounds.height || 0)));
        const clonedElement = await this._cloneSoftbridgeReceiptForCapture(targetElement);
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
        const renderedImage = await this._loadSoftbridgeImage(svgUrl);
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
        return canvas.toDataURL("image/png");
    },

    _resolveSoftbridgeReceiptElement(receiptElement) {
        if (!receiptElement) {
            return null;
        }
        const visualRootSelector = ".pos-receipt-print, .pos-receipt-container .d-inline-block";
        if (receiptElement.matches?.(visualRootSelector)) {
            return receiptElement;
        }
        const closestVisualRoot = receiptElement.closest?.(visualRootSelector);
        if (closestVisualRoot) {
            return closestVisualRoot;
        }
        if (receiptElement.classList?.contains("pos-receipt")) {
            return receiptElement;
        }
        return receiptElement.querySelector?.(".pos-receipt") || receiptElement;
    },

    async _cloneSoftbridgeReceiptForCapture(sourceElement) {
        const clonedElement = sourceElement.cloneNode(true);
        this._copySoftbridgeComputedStyles(sourceElement, clonedElement);
        this._syncSoftbridgeFormValues(sourceElement, clonedElement);
        this._copySoftbridgeCanvasContent(sourceElement, clonedElement);
        await this._inlineSoftbridgeImages(sourceElement, clonedElement);
        await this._inlineSoftbridgeBackgroundImages(sourceElement, clonedElement);
        clonedElement.style.overflow = "visible";
        clonedElement.style.maxHeight = "none";
        return clonedElement;
    },

    _copySoftbridgeComputedStyles(sourceNode, clonedNode) {
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
            this._copySoftbridgeComputedStyles(sourceChildren[index], clonedChildren[index]);
        }
    },

    _syncSoftbridgeFormValues(sourceNode, clonedNode) {
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
    },

    _copySoftbridgeCanvasContent(sourceNode, clonedNode) {
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
                // Ignore canvas copy errors and keep the cloned canvas element.
            }
        });
    },

    async _inlineSoftbridgeImages(sourceNode, clonedNode) {
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
                const dataUrl = await this._convertSoftbridgeImageToDataUrl(currentSrc);
                clonedImage.setAttribute("src", dataUrl);
            } catch {
                clonedImage.setAttribute("src", currentSrc);
            }
        });
        await Promise.all(tasks);
    },

    async _inlineSoftbridgeBackgroundImages(sourceNode, clonedNode) {
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
                    const dataUrl = await this._convertSoftbridgeImageToDataUrl(url);
                    inlinedBackgroundImage = inlinedBackgroundImage.replace(url, dataUrl);
                } catch {
                    // Keep the original URL if it cannot be inlined.
                }
            }
            clonedElement.style.setProperty("background-image", inlinedBackgroundImage);
        });
        await Promise.all(tasks);
    },

    async _convertSoftbridgeImageToDataUrl(url) {
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
    },

    async _loadSoftbridgeImage(source) {
        return await new Promise((resolve, reject) => {
            const image = new Image();
            image.decoding = "sync";
            image.onload = () => resolve(image);
            image.onerror = () => reject(new Error("Receipt image rendering failed."));
            image.src = source;
        });
    },

    _getSoftbridgeReceiptData() {
        const activeOrder = this.pos?.get_order?.() || this.currentOrder;
        if (!activeOrder || !this.pos?.orderExportForPrinting) {
            return null;
        }
        return this.pos.orderExportForPrinting(activeOrder);
    },

    _buildSoftbridgeTextReceipt(receiptData, receiptElement) {
        const domText = this._normalizeSoftbridgeReceiptText(
            receiptElement?.innerText || receiptElement?.textContent || ""
        );
        if (domText) {
            return domText;
        }

        if (!receiptData) {
            throw new Error("Receipt text was not available from the POS screen or order export.");
        }

        const lines = [];
        const pushLine = (value = "") => {
            const normalized = this._normalizeSoftbridgeReceiptText(value).trimEnd();
            lines.push(normalized);
        };
        const money = (value) => {
            if (value === undefined || value === null || value === "") {
                return "";
            }
            const numberValue = Number(value);
            if (Number.isNaN(numberValue)) {
                return String(value);
            }
            return this.env.utils.formatCurrency(numberValue);
        };

        const header = receiptData.headerData || {};
        const company = header.company || {};
        pushLine(company.name || "");
        pushLine(receiptData.name || "");
        pushLine(receiptData.date || "");
        pushLine(header.cashier || "");
        if (header.header) {
            pushLine(header.header);
        }
        pushLine("");

        for (const line of receiptData.orderlines || []) {
            const qty = line.qty ?? line.quantity ?? "";
            const name =
                line.productName ||
                line.product_name ||
                line.full_product_name ||
                line.name ||
                "";
            const total =
                line.price_display ||
                line.price ||
                line.price_with_tax ||
                line.price_subtotal_incl;

            pushLine(`${qty} ${name}`.trim());
            if (total !== undefined) {
                pushLine(money(total));
            }
            if (line.customerNote) {
                pushLine(`Note: ${line.customerNote}`);
            }
        }

        pushLine("");
        const taxTotals = receiptData.taxTotals || {};
        for (const subtotal of taxTotals.subtotals || []) {
            pushLine(`${subtotal.name}: ${money(subtotal.base_amount_currency)}`.trim());
            for (const taxGroup of subtotal.tax_groups || []) {
                pushLine(`${taxGroup.group_name}: ${money(taxGroup.tax_amount_currency)}`.trim());
            }
        }

        pushLine(`${receiptData.label_total || "Total"}: ${money(taxTotals.order_sign ? taxTotals.order_sign * taxTotals.order_total : taxTotals.order_total)}`.trim());
        if (receiptData.show_rounding) {
            pushLine(`${receiptData.label_rounding || "Rounding"}: ${money(taxTotals.order_sign ? taxTotals.order_sign * taxTotals.order_rounding : taxTotals.order_rounding)}`.trim());
        }
        if (receiptData.show_change) {
            pushLine(`${receiptData.label_change || "Change"}: ${money(receiptData.order_change)}`.trim());
        }

        for (const paymentLine of receiptData.paymentlines || []) {
            pushLine(`${paymentLine.name || "Payment"}: ${money(paymentLine.amount)}`.trim());
        }

        if (receiptData.total_discount) {
            pushLine(`${receiptData.label_discounts || "Discounts"}: ${money(receiptData.total_discount)}`.trim());
        }

        if (receiptData.footer) {
            pushLine("");
            pushLine(receiptData.footer);
        }

        if (receiptData.ticket_code) {
            pushLine("");
            pushLine(`Unique Code: ${receiptData.ticket_code}`);
        }

        return this._normalizeSoftbridgeReceiptText(lines.join("\n").replace(/\n{3,}/g, "\n\n"));
    },

    _normalizeSoftbridgeReceiptText(value) {
        return String(value ?? "")
            .replace(/[\u00A0\u1680\u2000-\u200A\u202F\u205F\u3000]/g, " ")
            .replace(/\r\n/g, "\n")
            .replace(/\r/g, "\n")
            .replace(/[ \t]+\n/g, "\n")
            .replace(/\n{3,}/g, "\n\n")
            .trim();
    },

    async _postSoftbridgePayload(endpoint, bridgeConfig, payload) {
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
            const parsedBody = rawBody ? this._safeParseSoftbridgeJson(rawBody) : {};
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
    },

    _notifySoftbridge(message, type = "info") {
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

    _safeParseSoftbridgeJson(rawBody) {
        try {
            return JSON.parse(rawBody);
        } catch {
            return { raw: rawBody };
        }
    },

    async _waitForSoftbridgeReceiptElement() {
        for (let index = 0; index < 5; index += 1) {
            const receiptElement = this._findSoftbridgeReceiptElement();
            if (receiptElement) {
                return receiptElement;
            }

            // Wait for the receipt subtree to settle before trying again.
            await new Promise((resolve) => window.requestAnimationFrame(resolve));
        }

        return null;
    },

    _findSoftbridgeReceiptElement() {
        const searchRoots = [this.el, document].filter(Boolean);
        for (const root of searchRoots) {
            for (const selector of RECEIPT_SELECTORS) {
                const receiptElement = root.querySelector?.(selector);
                if (receiptElement) {
                    return receiptElement;
                }
            }
        }
        return null;
    },
});
