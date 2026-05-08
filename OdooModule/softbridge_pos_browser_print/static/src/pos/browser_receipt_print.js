/** @odoo-module **/

import { ReceiptScreen } from "@point_of_sale/app/screens/receipt_screen/receipt_screen";
import { patch } from "@web/core/utils/patch";
import { onMounted, onPatched, useState } from "@odoo/owl";
import { useService } from "@web/core/utils/hooks";

const AUTO_PRINT_STATES = new Map();
const RECEIPT_SELECTORS = [
    ".receipt-screen .pos-receipt",
    ".pos-receipt-container .pos-receipt",
    ".pos-receipt",
    ".pos-receipt-container .d-inline-block",
    ".pos-receipt-print",
];

patch(ReceiptScreen.prototype, {
    setup() {
        super.setup(...arguments);
        this.notification = this.notification || useService("notification");
        this.browserReceiptPrint = useState({ status: "idle", error: null });

        onMounted(() => {
            void this._maybeAutoPrintReceiptInBrowser();
        });
        onPatched(() => {
            void this._maybeAutoPrintReceiptInBrowser();
        });
    },

    async onPrintInBrowserClick() {
        try {
            await this._callBrowserReceiptPrint({ manual: true });
        } catch (error) {
            // The receipt print flow already reports the error.
        }
    },

    async _callBrowserReceiptPrint({ manual = false } = {}) {
        if (this.browserReceiptPrint.status === "loading") {
            return;
        }

        this.browserReceiptPrint.status = "loading";
        this.browserReceiptPrint.error = null;
        try {
            await this._printReceiptInBrowser({ manual });
            this.browserReceiptPrint.status = "success";
        } catch (error) {
            this.browserReceiptPrint.status = "error";
            this.browserReceiptPrint.error = error;
            throw error;
        }
    },

    async _maybeAutoPrintReceiptInBrowser() {
        const config = this._getBrowserReceiptPrintConfig();
        if (!config.enabled || !config.autoPrintReceipt) {
            return;
        }

        const orderKey = this._getBrowserReceiptPrintOrderKey();
        if (!orderKey || AUTO_PRINT_STATES.has(orderKey)) {
            return;
        }

        AUTO_PRINT_STATES.set(orderKey, "pending");
        try {
            await this._callBrowserReceiptPrint({ manual: false });
            AUTO_PRINT_STATES.set(orderKey, "success");
        } catch (error) {
            AUTO_PRINT_STATES.set(orderKey, "failed");
            this._notifyBrowserReceiptPrint(
                `Browser receipt print failed: ${error.message || "Unknown error"}`,
                "warning"
            );
        }
    },

    _getBrowserReceiptPrintConfig() {
        const config = this.pos.config || {};
        return {
            enabled: Boolean(config.browser_receipt_print_enabled),
            autoPrintReceipt: Boolean(config.browser_receipt_auto_print),
            manualButton: Boolean(config.browser_receipt_manual_button),
        };
    },

    _getBrowserReceiptPrintOrderKey() {
        const order = this.currentOrder;
        return [
            order?.uid,
            order?.uuid,
            order?.name,
            order?.trackingNumber,
            order?.server_id,
        ].find(Boolean) || null;
    },

    async _printReceiptInBrowser({ manual = false } = {}) {
        const config = this._getBrowserReceiptPrintConfig();
        if (!config.enabled) {
            const error = new Error("Browser receipt print is disabled for this POS configuration.");
            if (manual) {
                this._notifyBrowserReceiptPrint(error.message, "warning");
            }
            throw error;
        }

        const receiptElement = await this._waitForBrowserReceiptElement();
        if (!receiptElement) {
            const error = new Error("Receipt element was not available on the receipt screen.");
            if (manual) {
                this._notifyBrowserReceiptPrint(error.message, "danger");
            }
            throw error;
        }

        const resolvedReceiptElement = this._resolveBrowserReceiptElement(receiptElement);
        const printableHtml = await this._buildBrowserPrintableHtml(resolvedReceiptElement);
        await this._openBrowserPrintFrame(printableHtml);

        if (manual) {
            this._notifyBrowserReceiptPrint("Opened the browser print dialog for the current receipt.", "success");
        }
    },

    async _buildBrowserPrintableHtml(receiptElement) {
        const targetElement = this._resolveBrowserReceiptElement(receiptElement);
        if (!targetElement) {
            throw new Error("Receipt content was not available to print.");
        }

        const clonedElement = await this._cloneBrowserReceiptForPrint(targetElement);
        const bounds = targetElement.getBoundingClientRect?.() || {};
        const width = Math.max(
            280,
            Math.ceil(targetElement.scrollWidth || bounds.width || 0)
        );
        const title = this._getBrowserReceiptPrintOrderKey() || "POS Receipt";

        const wrapper = document.createElement("div");
        wrapper.className = "softbridge-browser-print-root";
        wrapper.appendChild(clonedElement);

        return `
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>${this._escapeBrowserPrintText(title)}</title>
    <style>
        @page {
            margin: 0;
            size: auto;
        }
        html, body {
            margin: 0;
            padding: 0;
            background: #ffffff;
            color: #000000;
        }
        body {
            width: ${width}px;
            min-width: ${width}px;
        }
        .softbridge-browser-print-root {
            width: ${width}px;
            min-width: ${width}px;
            margin: 0;
            padding: 0;
            background: #ffffff;
        }
        .softbridge-browser-print-root * {
            box-sizing: border-box;
        }
        @media print {
            html, body {
                width: ${width}px;
                min-width: ${width}px;
            }
            .softbridge-browser-print-root {
                width: ${width}px;
                min-width: ${width}px;
            }
        }
    </style>
</head>
<body>${wrapper.innerHTML}</body>
</html>
        `.trim();
    },

    async _openBrowserPrintFrame(printableHtml) {
        return new Promise((resolve, reject) => {
            const frame = document.createElement("iframe");
            frame.style.position = "fixed";
            frame.style.right = "0";
            frame.style.bottom = "0";
            frame.style.width = "0";
            frame.style.height = "0";
            frame.style.border = "0";
            frame.style.visibility = "hidden";
            frame.setAttribute("sandbox", "allow-modals allow-same-origin");

            const cleanup = () => {
                setTimeout(() => {
                    frame.remove();
                }, 0);
            };

            frame.onload = () => {
                const frameWindow = frame.contentWindow;
                if (!frameWindow) {
                    cleanup();
                    reject(new Error("Browser print frame was not available."));
                    return;
                }

                const handleAfterPrint = () => {
                    frameWindow.removeEventListener("afterprint", handleAfterPrint);
                    cleanup();
                    resolve();
                };

                frameWindow.addEventListener("afterprint", handleAfterPrint);
                setTimeout(() => {
                    try {
                        frameWindow.focus();
                        frameWindow.print();
                        setTimeout(() => {
                            cleanup();
                            resolve();
                        }, 1200);
                    } catch (error) {
                        cleanup();
                        reject(error);
                    }
                }, 150);
            };

            document.body.appendChild(frame);
            frame.srcdoc = printableHtml;
        });
    },

    async _cloneBrowserReceiptForPrint(sourceElement) {
        const clonedElement = sourceElement.cloneNode(true);
        this._copyBrowserPrintComputedStyles(sourceElement, clonedElement);
        this._syncBrowserPrintFormValues(sourceElement, clonedElement);
        this._copyBrowserPrintCanvasContent(sourceElement, clonedElement);
        await this._inlineBrowserPrintImages(sourceElement, clonedElement);
        await this._inlineBrowserPrintBackgroundImages(sourceElement, clonedElement);
        clonedElement.style.overflow = "visible";
        clonedElement.style.maxHeight = "none";
        clonedElement.style.margin = "0";
        return clonedElement;
    },

    _copyBrowserPrintComputedStyles(sourceNode, clonedNode) {
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
            this._copyBrowserPrintComputedStyles(sourceChildren[index], clonedChildren[index]);
        }
    },

    _syncBrowserPrintFormValues(sourceNode, clonedNode) {
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

    _copyBrowserPrintCanvasContent(sourceNode, clonedNode) {
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
                image.style.width = `${sourceCanvas.width}px`;
                image.style.height = `${sourceCanvas.height}px`;
                clonedCanvas.replaceWith(image);
            } catch {
                // Ignore canvas export failures and continue with the cloned canvas.
            }
        });
    },

    async _inlineBrowserPrintImages(sourceNode, clonedNode) {
        const sourceImages = sourceNode.querySelectorAll?.("img") || [];
        const clonedImages = clonedNode.querySelectorAll?.("img") || [];
        await Promise.all(
            Array.from(sourceImages).map(async (sourceImage, index) => {
                const clonedImage = clonedImages[index];
                if (!(sourceImage instanceof HTMLImageElement) || !(clonedImage instanceof HTMLImageElement)) {
                    return;
                }

                const dataUrl = await this._resolveBrowserPrintImageDataUrl(sourceImage.currentSrc || sourceImage.src);
                if (dataUrl) {
                    clonedImage.src = dataUrl;
                }
            })
        );
    },

    async _inlineBrowserPrintBackgroundImages(sourceNode, clonedNode) {
        if (!(sourceNode instanceof Element) || !(clonedNode instanceof Element)) {
            return;
        }

        const computedStyle = window.getComputedStyle(sourceNode);
        const backgroundImage = computedStyle.getPropertyValue("background-image");
        const match = backgroundImage.match(/url\((['"]?)(.*?)\1\)/);
        if (match?.[2]) {
            const dataUrl = await this._resolveBrowserPrintImageDataUrl(match[2]);
            if (dataUrl) {
                clonedNode.style.setProperty("background-image", `url("${dataUrl}")`);
            }
        }

        const sourceChildren = Array.from(sourceNode.children);
        const clonedChildren = Array.from(clonedNode.children);
        await Promise.all(
            sourceChildren.map((child, index) =>
                this._inlineBrowserPrintBackgroundImages(child, clonedChildren[index])
            )
        );
    },

    async _resolveBrowserPrintImageDataUrl(source) {
        const normalizedSource = (source || "").trim();
        if (!normalizedSource) {
            return null;
        }
        if (normalizedSource.startsWith("data:")) {
            return normalizedSource;
        }

        try {
            const response = await fetch(normalizedSource, { mode: "cors", credentials: "include" });
            if (!response.ok) {
                return null;
            }
            const blob = await response.blob();
            return await this._readBrowserPrintBlobAsDataUrl(blob);
        } catch {
            return null;
        }
    },

    _readBrowserPrintBlobAsDataUrl(blob) {
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = () => resolve(reader.result);
            reader.onerror = () => reject(reader.error || new Error("Image could not be read."));
            reader.readAsDataURL(blob);
        });
    },

    async _waitForBrowserReceiptElement() {
        const attempts = 20;
        for (let attempt = 0; attempt < attempts; attempt += 1) {
            const receiptElement = this._findBrowserReceiptElement();
            if (receiptElement) {
                return receiptElement;
            }
            await new Promise((resolve) => setTimeout(resolve, 100));
        }
        return null;
    },

    _findBrowserReceiptElement() {
        for (const selector of RECEIPT_SELECTORS) {
            const element = this.el?.querySelector?.(selector) || document.querySelector(selector);
            if (element) {
                return element;
            }
        }
        return null;
    },

    _resolveBrowserReceiptElement(receiptElement) {
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
        return receiptElement;
    },

    _notifyBrowserReceiptPrint(message, type = "info") {
        if (!message || !this.notification?.add) {
            return;
        }
        this.notification.add(message, { type });
    },

    _escapeBrowserPrintText(value) {
        return String(value || "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#39;");
    },
});
