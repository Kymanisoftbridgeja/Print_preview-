/** @odoo-module **/

import { loadAllImages } from "@point_of_sale/utils";
import { ReceiptScreen } from "@point_of_sale/app/screens/receipt_screen/receipt_screen";
import { ControlButtons } from "@point_of_sale/app/screens/product_screen/control_buttons/control_buttons";
import { OrderReceipt } from "@point_of_sale/app/screens/receipt_screen/receipt/order_receipt";
import { patch } from "@web/core/utils/patch";
import { useState, onMounted, onPatched } from "@odoo/owl";
import { useService } from "@web/core/utils/hooks";

const AUTO_SEND_STATES = new Map();
const DEFAULT_TIMEOUT_MS = 5000;
const DEFAULT_BRIDGE_URL = "http://127.0.0.1:9900";
const BRIDGE_RECEIPT_PATH = "/odoo/receipt";
const BRIDGE_STATUS_PATH = "/integration/status";
const BRIDGE_PROBE_TIMEOUT_MS = 2500;
const BRIDGE_PROBE_CACHE_MS = 15000;
const RESTAURANT_BILL_SOURCE = "odoo_pos_restaurant_bill_action";
const BILL_CAPTURE_HOST_CLASS = "softbridge-bill-capture-host";
const RECEIPT_SELECTORS = [
    ".receipt-screen .pos-receipt",
    ".pos-receipt-container .pos-receipt",
    ".pos-receipt",
    ".pos-receipt-container .d-inline-block",
    ".pos-receipt-print",
];
const SOFTBRIDGE_RUNTIME = {
    controlButtons: null,
    receiptScreen: null,
    originalWindowPrint: null,
    windowPrintPatched: false,
    billBridgeInFlight: false,
    bridgeProbeInFlight: null,
    lastBridgeProbeStartedAt: 0,
    lastBridgeProbeOkAt: 0,
};

patch(ControlButtons.prototype, {
    setup() {
        super.setup(...arguments);
        this.renderer = this.renderer || useService("renderer");
        this.notification = this.notification || useService("notification");
        SOFTBRIDGE_RUNTIME.controlButtons = this;
        ensureSoftbridgeWindowPrintPatch();

        Promise.resolve().then(() => {
            this._wrapSoftbridgeRestaurantBillAction();
            quietlyProbeSoftbridgeDesktopBridgeFromPos(this.pos);
        });
    },

    getSoftbridgeRestaurantBillOrderlineCount() {
        const order = resolveSoftbridgeCurrentOrder(this);
        return getSoftbridgeOrderlineCount(order);
    },

    async onSendRestaurantBillToSoftbridgeClick() {
        console.info("[Softbridge][Bill] Dedicated Softbridge bill button clicked.");
        try {
            await sendRestaurantBillToSoftbridge(this);
            this.dialog?.closeAll?.();
            this.notification?.add?.("Restaurant bill sent to Softbridge.", {
                type: "success",
            });
            return true;
        } catch (error) {
            const message = error?.message || "Unknown error";
            console.warn("[Softbridge][Bill] Dedicated Softbridge bill button failed.", error);
            this.notification?.add?.(`Softbridge bill send failed: ${message}`, {
                type: "warning",
            });
            return false;
        }
    },

    _wrapSoftbridgeRestaurantBillAction() {
        if (this.__softbridgeBillActionWrapped) {
            return;
        }

        if (typeof this.clickPrintBill !== "function") {
            return;
        }

        const originalClickPrintBill = this.clickPrintBill.bind(this);
        this.clickPrintBill = async (...args) => {
            if (await maybeSendRestaurantBillToSoftbridge(this, { notify: false })) {
                return true;
            }
            return await originalClickPrintBill(...args);
        };
        this.__softbridgeBillActionWrapped = true;
    },
});

async function maybeSendRestaurantBillToSoftbridge(controlButtons, { notify = false } = {}) {
    try {
        await sendRestaurantBillToSoftbridge(controlButtons);
        return true;
    } catch (error) {
        if (notify) {
            const message = error?.message || "Unknown error";
            controlButtons?.notification?.add?.(`Softbridge bill send failed: ${message}`, {
                type: "warning",
            });
        }
        console.warn("[Softbridge][Bill] Desktop bridge send failed.", error);
        return false;
    }
}

async function sendRestaurantBillToSoftbridge(controlButtons) {
    const pos = controlButtons?.pos;
    const config = pos?.config || {};
    if (
        !config.softbridge_enabled ||
        !config.softbridge_restaurant_bill_action_enabled ||
        !config.module_pos_restaurant
    ) {
        throw new Error("Bill printing is not enabled for this POS configuration.");
    }

    const orderInfo = resolveSoftbridgeCurrentOrderInfo(controlButtons);
    const order = orderInfo.order;
    if (!order) {
        console.warn(
            "[Softbridge][Bill] Active order resolution failed.",
            buildSoftbridgeOrderResolutionDiagnostics(controlButtons)
        );
        throw new Error("The active POS order could not be resolved for bill printing.");
    }

    console.info("[Softbridge][Bill] Active POS order resolved.", {
        resolutionSource: orderInfo.source,
        orderUid: getSoftbridgeOrderCandidateKey(order),
        orderName: getSoftbridgeOrderDisplayName(order),
        orderlineCount: getSoftbridgeOrderlineCount(order),
    });

    const billData = buildSoftbridgeRestaurantBillReceiptData(pos, order);
    const bridgeConfig = buildSoftbridgeConfigFromPosConfig(config);
    const receiptEndpoint = buildSoftbridgePathEndpoint(bridgeConfig, BRIDGE_RECEIPT_PATH);
    const statusEndpoint = buildSoftbridgePathEndpoint(bridgeConfig, BRIDGE_STATUS_PATH);
    const orderKey = getSoftbridgeOrderCandidateKey(order);

    console.info("[Softbridge][Bill] Custom bill print triggered.", {
        orderKey,
        orderName: order?.name || billData?.name || "",
        ticketNumber: resolveSoftbridgeTicketNumber(order, billData),
        cashier: billData?.headerData?.cashier || getSoftbridgeOrderCashierName(order) || "",
        orderlineCount: Array.isArray(billData?.orderlines)
            ? billData.orderlines.length
            : getSoftbridgeOrderlineCount(order),
        posConfigName: resolveSoftbridgePosConfigName(config),
        bridgeUrl: receiptEndpoint,
    });

    let renderedBill = null;
    let bridgeStatus = null;
    try {
        try {
            bridgeStatus = await probeSoftbridgeDesktopBridge(bridgeConfig, { force: true });
            console.info("[Softbridge][Bill] Desktop bridge status confirmed.", {
                status: bridgeStatus?.status,
                printerName:
                    bridgeStatus?.default_printer_name ||
                    bridgeStatus?.defaultPrinterName ||
                    "",
                savedPrinterCount: bridgeStatus?.saved_printer_count,
            });
        } catch (error) {
            console.warn(
                "[Softbridge][Bill] Desktop bridge status probe failed; attempting receipt POST anyway.",
                {
                    statusUrl: statusEndpoint,
                    receiptUrl: receiptEndpoint,
                    error: error?.message || String(error || "Unknown status probe error"),
                }
            );
        }

        renderedBill = await getSoftbridgeRestaurantBillReceiptRoot(
            controlButtons,
            billData,
            order
        );
        const receiptRoot = renderedBill.receiptRoot;
        const billHtml = getSoftbridgeElementHtml(receiptRoot);
        const billText = normalizeSoftbridgeRestaurantBillText(
            receiptRoot?.innerText || receiptRoot?.textContent || ""
        );
        console.info("[Softbridge][Bill] Receipt HTML rendered.", {
            htmlLength: billHtml.length,
            textLength: billText.length,
            usedLivePreviewDom: Boolean(renderedBill.usedLivePreviewDom),
        });

        if (!receiptRoot) {
            throw new Error("Empty bill HTML: the Pro forma receipt element was not rendered.");
        }

        if (!billHtml.length && !billText.length) {
            throw new Error("Empty bill HTML: the rendered Pro forma receipt has no printable content.");
        }

        try {
            await loadAllImages(receiptRoot);
        } catch (error) {
            console.warn("[Softbridge][Bill] Receipt assets did not fully preload.", error);
        }

        console.info("[Softbridge][Bill] Building direct desktop bridge payload.", {
            orderUid: orderKey,
            orderName: order?.name || billData?.name || "",
            bridgeUrl: receiptEndpoint,
            billHtmlLength: billHtml.length,
            billTextLength: billText.length,
        });
        const payload = await buildSoftbridgeRestaurantBillBridgePayload({
            bridgeConfig,
            order,
            receiptData: billData,
            receiptRoot,
            billHtml,
            billText,
        });

        console.info("[Softbridge][Bill] Payload prepared for desktop bridge.", {
            orderUid: orderKey,
            orderName: order?.name || billData?.name || "",
            ticketNumber: resolveSoftbridgeTicketNumber(order, billData),
            posConfigName: resolveSoftbridgePosConfigName(config),
            printerName: bridgeStatus?.default_printer_name || bridgeStatus?.defaultPrinterName || "",
            bridgeUrl: receiptEndpoint,
            payloadKeys: Object.keys(payload || {}),
            payloadType: payload.type,
            captureMode: payload.capture_mode,
            hasStructuredReceipt: Boolean(payload.structured_receipt),
            cashier: payload.structured_receipt?.cashier || "",
            orderlineCount: payload.structured_receipt?.lines?.length || 0,
            qrBarcodeFound: Boolean(
                payload.structured_receipt?.qr_value ||
                    payload.structured_receipt?.qr_image ||
                    payload.structured_receipt?.barcode_value ||
                    payload.structured_receipt?.barcode_image
            ),
            htmlLength: String(payload.html || "").length,
            textLength: String(payload.text || "").length,
        });

        console.info("[Softbridge][Bill] Bridge send started.", {
            bridgeUrl: receiptEndpoint,
            orderUid: orderKey,
            orderName: order?.name || billData?.name || "",
            printType: payload.print_type,
            documentType: payload.document_type,
        });
        const responseBody = await postSoftbridgePayloadToDesktop(
            receiptEndpoint,
            bridgeConfig,
            payload,
            { logPrefix: "[Softbridge][Bill]" }
        );
        console.info("[Softbridge][Bill] Bridge success.", {
            responseBody,
        });

        return {
            endpoint: receiptEndpoint,
            payload,
            responseBody,
        };
    } catch (error) {
        console.warn("[Softbridge][Bill] Bridge failure.", error);
        throw enrichSoftbridgeBillError(error);
    } finally {
        renderedBill?.cleanup?.();
    }
}

function ensureSoftbridgeWindowPrintPatch() {
    if (SOFTBRIDGE_RUNTIME.windowPrintPatched || typeof window === "undefined") {
        return;
    }

    SOFTBRIDGE_RUNTIME.originalWindowPrint = window.print.bind(window);
    window.print = () => {
        interceptSoftbridgeRestaurantBillWindowPrint();
    };
    SOFTBRIDGE_RUNTIME.windowPrintPatched = true;
}

async function interceptSoftbridgeRestaurantBillWindowPrint() {
    if (SOFTBRIDGE_RUNTIME.billBridgeInFlight) {
        return;
    }

    const originalWindowPrint = SOFTBRIDGE_RUNTIME.originalWindowPrint;
    const controlButtons = SOFTBRIDGE_RUNTIME.controlButtons;
    if (!controlButtons) {
        originalWindowPrint?.();
        return;
    }

    if (!shouldInterceptSoftbridgeRestaurantBillPrint(controlButtons)) {
        originalWindowPrint?.();
        return;
    }

    SOFTBRIDGE_RUNTIME.billBridgeInFlight = true;
    try {
        console.info("[Softbridge][Bill] Browser print intercepted.");
        const sent = await maybeSendRestaurantBillToSoftbridge(controlButtons, { notify: true });
        if (sent) {
            return;
        }
    } catch (error) {
        console.warn("[Softbridge][Bill] Browser print interception failed.", error);
    } finally {
        SOFTBRIDGE_RUNTIME.billBridgeInFlight = false;
    }

    console.warn("[Softbridge][Bill] Falling back to browser print preview.");
    originalWindowPrint?.();
}

function shouldInterceptSoftbridgeRestaurantBillPrint(controlButtons) {
    const pos = controlButtons?.pos;
    const config = pos?.config || {};
    if (
        !config.softbridge_enabled ||
        !config.softbridge_restaurant_bill_action_enabled ||
        !config.module_pos_restaurant
    ) {
        return false;
    }

    const activeReceiptElement = findSoftbridgeReceiptElementInDocument();
    const activeReceiptText = normalizeSoftbridgeRestaurantBillText(
        activeReceiptElement?.innerText ||
            activeReceiptElement?.textContent ||
            document?.body?.innerText ||
            ""
    );
    return /\bpro\s*forma(?:\s+receipt)?\b/i.test(activeReceiptText);
}

async function getSoftbridgeRestaurantBillReceiptRoot(controlButtons, billData, order) {
    const liveReceiptElement = findSoftbridgeReceiptElementInDocument();
    if (looksLikeSoftbridgeRestaurantBillReceipt(liveReceiptElement)) {
        console.info("[Softbridge][Bill] Using live bill preview DOM for bridge send.");
        return {
            receiptRoot: liveReceiptElement,
            usedLivePreviewDom: true,
            cleanup: null,
        };
    }

    let renderedReceiptRoot = null;
    try {
        console.info("[Softbridge][Bill] Rendering bill receipt with Odoo OrderReceipt props.", {
            orderUid: getSoftbridgeOrderCandidateKey(order),
            orderName: getSoftbridgeOrderDisplayName(order),
            propKeys: ["order", "basic_receipt"],
        });
        renderedReceiptRoot = normalizeSoftbridgeRenderedReceiptRoot(
            await controlButtons.renderer.toHtml(OrderReceipt, {
                order,
                basic_receipt: false,
            })
        );
    } catch (error) {
        console.warn(
            "[Softbridge][Bill] Odoo OrderReceipt order-prop render failed; trying legacy data-prop render.",
            error
        );
        renderedReceiptRoot = normalizeSoftbridgeRenderedReceiptRoot(
            await controlButtons.renderer.toHtml(OrderReceipt, {
                data: billData,
                formatCurrency: controlButtons?.env?.utils?.formatCurrency,
            })
        );
    }
    renderedReceiptRoot.classList?.add("pos-receipt-print", "p-3");

    const captureHost = document.createElement("div");
    captureHost.className = BILL_CAPTURE_HOST_CLASS;
    captureHost.style.position = "fixed";
    captureHost.style.left = "-10000px";
    captureHost.style.top = "0";
    captureHost.style.width = "420px";
    captureHost.style.background = "#ffffff";
    captureHost.style.pointerEvents = "none";
    captureHost.style.opacity = "0";
    captureHost.style.zIndex = "-1";
    captureHost.appendChild(renderedReceiptRoot);
    document.body.appendChild(captureHost);
    await waitForSoftbridgeAnimationFrames(2);

    return {
        receiptRoot: renderedReceiptRoot,
        usedLivePreviewDom: false,
        cleanup: () => captureHost.remove(),
    };
}

function normalizeSoftbridgeRenderedReceiptRoot(renderedReceiptRoot) {
    if (!renderedReceiptRoot) {
        throw new Error("The Pro forma receipt renderer returned no receipt HTML.");
    }

    if (renderedReceiptRoot instanceof Element) {
        return renderedReceiptRoot;
    }

    if (renderedReceiptRoot instanceof DocumentFragment) {
        const wrapper = document.createElement("div");
        wrapper.className = "pos-receipt-print p-3";
        wrapper.appendChild(renderedReceiptRoot);
        return wrapper;
    }

    throw new Error("The Pro forma receipt renderer returned an unsupported receipt node.");
}

function waitForSoftbridgeAnimationFrames(frameCount = 1) {
    let promise = Promise.resolve();
    for (let index = 0; index < frameCount; index += 1) {
        promise = promise.then(
            () => new Promise((resolve) => window.requestAnimationFrame(resolve))
        );
    }
    return promise;
}

function getSoftbridgeElementHtml(element) {
    if (!element) {
        return "";
    }

    return String(element.outerHTML || element.innerHTML || "").trim();
}

function resolveSoftbridgeTicketNumber(order, receiptData) {
    return [
        order?.trackingNumber,
        order?.tracking_number,
        receiptData?.trackingNumber,
        receiptData?.tracking_number,
        order?.name,
        receiptData?.name,
        order?.uid,
        order?.uuid,
        order?.ticketCode,
        order?.ticket_code,
        receiptData?.ticket_code,
    ]
        .map((value) => String(value || "").trim())
        .find(Boolean) || "";
}

function resolveSoftbridgePosConfigName(config = {}) {
    return [
        config.name,
        config.display_name,
        config.module_pos_restaurant ? "Restaurant POS" : "",
        config.id ? `POS config ${config.id}` : "",
    ]
        .map((value) => String(value || "").trim())
        .find(Boolean) || "";
}

function enrichSoftbridgeBillError(error) {
    if (error instanceof Error) {
        return error;
    }

    return new Error(String(error || "Unknown bill print error"));
}

function looksLikeSoftbridgeRestaurantBillReceipt(receiptElement) {
    if (!receiptElement) {
        return false;
    }
    const receiptText = normalizeSoftbridgeRestaurantBillText(
        receiptElement.innerText || receiptElement.textContent || ""
    );
    return /\bpro\s*forma(?:\s+receipt)?\b/i.test(receiptText);
}

function findSoftbridgeReceiptElementInDocument() {
    for (const selector of RECEIPT_SELECTORS) {
        const receiptElement = document.querySelector(selector);
        if (receiptElement) {
            return receiptElement;
        }
    }
    return null;
}

function normalizeSoftbridgeRestaurantBillText(value) {
    return String(value || "")
        .replace(/\r\n/g, "\n")
        .replace(/\r/g, "\n")
        .replace(/\s+/g, " ")
        .trim();
}

function resolveSoftbridgeCurrentOrder(source) {
    return resolveSoftbridgeCurrentOrderInfo(source).order;
}

function resolveSoftbridgeCurrentOrderInfo(source) {
    const checkedOrders = new Set();
    const directCandidates = [
        ["source", source],
        ["source.currentOrder", readSoftbridgeProperty(source, "currentOrder")],
        ["source.selectedOrder", readSoftbridgeProperty(source, "selectedOrder")],
        ["source.order", readSoftbridgeProperty(source, "order")],
        ["source.props.order", readSoftbridgeProperty(readSoftbridgeProperty(source, "props"), "order")],
        [
            "source.props.currentOrder",
            readSoftbridgeProperty(readSoftbridgeProperty(source, "props"), "currentOrder"),
        ],
        ["source.env.currentOrder", readSoftbridgeProperty(readSoftbridgeProperty(source, "env"), "currentOrder")],
    ];

    for (const [candidateSource, candidate] of directCandidates) {
        const orderInfo = resolveSoftbridgeOrderFromCandidate(
            candidate,
            candidateSource,
            checkedOrders
        );
        if (orderInfo.order) {
            return orderInfo;
        }
    }

    const checkedPosStores = new Set();
    for (const posCandidate of collectSoftbridgePosCandidates(source)) {
        const orderInfo = resolveSoftbridgeOrderFromPos(
            posCandidate.pos,
            posCandidate.source,
            checkedOrders,
            checkedPosStores
        );
        if (orderInfo.order) {
            return orderInfo;
        }
    }

    return { order: null, source: "" };
}

function collectSoftbridgePosCandidates(source) {
    const candidates = [
        { source: "source.pos", pos: readSoftbridgeProperty(source, "pos") },
        { source: "source", pos: source },
        {
            source: "source.env.pos",
            pos: readSoftbridgeProperty(readSoftbridgeProperty(source, "env"), "pos"),
        },
        {
            source: "source.env.services.pos",
            pos: readSoftbridgeProperty(
                readSoftbridgeProperty(readSoftbridgeProperty(source, "env"), "services"),
                "pos"
            ),
        },
        {
            source: "source.__owl__.app.env.pos",
            pos: readSoftbridgeProperty(
                readSoftbridgeProperty(
                    readSoftbridgeProperty(readSoftbridgeProperty(source, "__owl__"), "app"),
                    "env"
                ),
                "pos"
            ),
        },
    ];
    const seen = new Set();
    return candidates.filter(({ pos }) => {
        if (!pos || typeof pos !== "object" || seen.has(pos)) {
            return false;
        }
        seen.add(pos);
        return true;
    });
}

function resolveSoftbridgeOrderFromPos(
    pos,
    posSource,
    checkedOrders,
    checkedPosStores
) {
    if (!pos || typeof pos !== "object" || checkedPosStores.has(pos)) {
        return { order: null, source: "" };
    }
    checkedPosStores.add(pos);

    for (const methodName of [
        "getOrder",
        "get_order",
        "getCurrentOrder",
        "get_current_order",
        "getSelectedOrder",
        "get_selected_order",
    ]) {
        const orderInfo = resolveSoftbridgeOrderFromCandidate(
            callSoftbridgeMethod(pos, methodName),
            `${posSource}.${methodName}()`,
            checkedOrders
        );
        if (orderInfo.order) {
            return orderInfo;
        }
    }

    for (const fieldName of [
        "selectedOrder",
        "currentOrder",
        "order",
        "activeOrder",
        "selected_order",
        "current_order",
    ]) {
        const orderInfo = resolveSoftbridgeOrderFromCandidate(
            readSoftbridgeProperty(pos, fieldName),
            `${posSource}.${fieldName}`,
            checkedOrders
        );
        if (orderInfo.order) {
            return orderInfo;
        }
    }

    const orderModel = readSoftbridgeProperty(readSoftbridgeProperty(pos, "models"), "pos.order");
    for (const orderUuid of collectSoftbridgeActiveOrderUuids(pos)) {
        const orderInfo = resolveSoftbridgeOrderFromCandidate(
            callSoftbridgeMethod(orderModel, "getBy", "uuid", orderUuid),
            `${posSource}.models["pos.order"].getBy("uuid", ${orderUuid})`,
            checkedOrders
        );
        if (orderInfo.order) {
            return orderInfo;
        }
    }

    for (const methodName of [
        "getOpenOrders",
        "get_open_orders",
        "getOrders",
        "get_orders",
    ]) {
        const orderInfo = findSoftbridgeUsableOrderInCollection(
            callSoftbridgeMethod(pos, methodName),
            `${posSource}.${methodName}()`,
            checkedOrders
        );
        if (orderInfo.order) {
            return orderInfo;
        }
    }

    for (const collectionCandidate of collectSoftbridgeOrderCollectionCandidates(pos, posSource)) {
        const orderInfo = findSoftbridgeUsableOrderInCollection(
            collectionCandidate.collection,
            collectionCandidate.source,
            checkedOrders
        );
        if (orderInfo.order) {
            return orderInfo;
        }
    }

    return { order: null, source: "" };
}

function collectSoftbridgeActiveOrderUuids(pos) {
    const router = readSoftbridgeProperty(pos, "router");
    const routerState = readSoftbridgeProperty(router, "state");
    const routeParams = readSoftbridgeProperty(routerState, "params");
    return [
        readSoftbridgeProperty(routeParams, "orderUuid"),
        readSoftbridgeProperty(routeParams, "order_uuid"),
        readSoftbridgeProperty(pos, "selectedOrderUuid"),
        readSoftbridgeProperty(pos, "selected_order_uuid"),
        readSoftbridgeProperty(pos, "orderUuid"),
        readSoftbridgeProperty(pos, "order_uuid"),
        readSoftbridgeProperty(readSoftbridgeProperty(pos, "uiState"), "selectedOrderUuid"),
    ]
        .map((value) => String(value || "").trim())
        .filter(Boolean);
}

function collectSoftbridgeOrderCollectionCandidates(pos, posSource) {
    const models = readSoftbridgeProperty(pos, "models");
    const db = readSoftbridgeProperty(pos, "db");
    const data = readSoftbridgeProperty(pos, "data");
    return [
        { source: `${posSource}.orders`, collection: readSoftbridgeProperty(pos, "orders") },
        {
            source: `${posSource}.models["pos.order"]`,
            collection: readSoftbridgeProperty(models, "pos.order"),
        },
        {
            source: `${posSource}.models["pos_order"]`,
            collection: readSoftbridgeProperty(models, "pos_order"),
        },
        { source: `${posSource}.models.pos_order`, collection: readSoftbridgeProperty(models, "pos_order") },
        { source: `${posSource}.db.orders`, collection: readSoftbridgeProperty(db, "orders") },
        {
            source: `${posSource}.data.models["pos.order"]`,
            collection: readSoftbridgeProperty(
                readSoftbridgeProperty(readSoftbridgeProperty(data, "models"), "pos.order"),
                "records"
            ),
        },
    ];
}

function resolveSoftbridgeOrderFromCandidate(candidate, candidateSource, checkedOrders) {
    if (!candidate || typeof candidate !== "object" || checkedOrders.has(candidate)) {
        return { order: null, source: "" };
    }
    checkedOrders.add(candidate);

    if (isSoftbridgeOrderCandidate(candidate)) {
        return { order: candidate, source: candidateSource };
    }

    return findSoftbridgeUsableOrderInCollection(
        candidate,
        `${candidateSource}.collection`,
        checkedOrders
    );
}

function findSoftbridgeUsableOrderInCollection(collection, collectionSource, checkedOrders) {
    const orders = normalizeSoftbridgeOrderCollection(collection);
    const candidateOrders = orders
        .filter((order) => order && typeof order === "object" && !checkedOrders.has(order))
        .filter(isSoftbridgeOrderCandidate);

    for (const order of candidateOrders) {
        checkedOrders.add(order);
    }

    const printableOrders = candidateOrders.filter(
        (order) => getSoftbridgeOrderlineCount(order) > 0 && !isSoftbridgeFinalizedOrder(order)
    );
    const resolvedOrder =
        printableOrders.find(isSoftbridgeSelectedOrderCandidate) ||
        printableOrders[0] ||
        candidateOrders.find((order) => !isSoftbridgeFinalizedOrder(order)) ||
        candidateOrders[0];

    return resolvedOrder
        ? { order: resolvedOrder, source: collectionSource }
        : { order: null, source: "" };
}

function normalizeSoftbridgeOrderCollection(collection) {
    if (!collection) {
        return [];
    }

    if (Array.isArray(collection)) {
        return collection;
    }

    for (const methodName of ["getAll", "readAll", "toArray", "getItems"]) {
        const values = callSoftbridgeMethod(collection, methodName);
        if (Array.isArray(values)) {
            return values;
        }
    }

    if (typeof readSoftbridgeProperty(collection, "filter") === "function") {
        try {
            const values = collection.filter((record) => Boolean(record));
            if (Array.isArray(values)) {
                return values;
            }
        } catch (error) {
            console.warn("[Softbridge][Bill] Could not filter POS order collection.", error);
        }
    }

    if (typeof readSoftbridgeProperty(collection, "reduce") === "function") {
        try {
            const values = collection.reduce((records, record) => {
                records.push(record);
                return records;
            }, []);
            if (Array.isArray(values)) {
                return values;
            }
        } catch (error) {
            console.warn("[Softbridge][Bill] Could not reduce POS order collection.", error);
        }
    }

    for (const fieldName of ["records", "models", "items"]) {
        const values = readSoftbridgeProperty(collection, fieldName);
        if (Array.isArray(values)) {
            return values;
        }
    }

    if (typeof collection[Symbol.iterator] === "function") {
        try {
            return Array.from(collection);
        } catch (error) {
            console.warn("[Softbridge][Bill] Could not read iterable POS order collection.", error);
        }
    }

    return [];
}

function isSoftbridgeOrderCandidate(value) {
    if (!value || typeof value !== "object") {
        return false;
    }

    return Boolean(
        typeof readSoftbridgeProperty(value, "getOrderlines") === "function" ||
            typeof readSoftbridgeProperty(value, "get_orderlines") === "function" ||
            readSoftbridgeProperty(value, "orderlines") ||
            readSoftbridgeProperty(value, "lines") ||
            typeof readSoftbridgeProperty(value, "export_for_printing") === "function" ||
            typeof readSoftbridgeProperty(value, "exportForPrinting") === "function"
    );
}

function isSoftbridgeSelectedOrderCandidate(order) {
    return Boolean(
        readSoftbridgeProperty(order, "isSelected") ||
            readSoftbridgeProperty(order, "selected") ||
            readSoftbridgeProperty(order, "is_current") ||
            readSoftbridgeProperty(order, "isCurrent")
    );
}

function isSoftbridgeFinalizedOrder(order) {
    const state = String(
        readSoftbridgeProperty(order, "state") ||
            readSoftbridgeProperty(order, "status") ||
            readSoftbridgeProperty(order, "finalized_state") ||
            ""
    ).toLowerCase();
    return Boolean(
        readSoftbridgeProperty(order, "finalized") ||
            readSoftbridgeProperty(order, "finalizedAt") ||
            state === "paid" ||
            state === "done" ||
            state === "invoiced" ||
            state === "cancel"
    );
}

function getSoftbridgeOrderCandidateKey(order) {
    return [
        readSoftbridgeProperty(order, "uid"),
        readSoftbridgeProperty(order, "uuid"),
        readSoftbridgeProperty(order, "name"),
        readSoftbridgeProperty(order, "trackingNumber"),
        readSoftbridgeProperty(order, "tracking_number"),
        readSoftbridgeProperty(order, "server_id"),
        readSoftbridgeProperty(order, "id"),
    ].find(Boolean) || "";
}

function getSoftbridgeOrderDisplayName(order) {
    return String(
        readSoftbridgeProperty(order, "name") ||
            readSoftbridgeProperty(order, "display_name") ||
            getSoftbridgeOrderCandidateKey(order) ||
            ""
    );
}

function buildSoftbridgeOrderResolutionDiagnostics(source) {
    const pos = readSoftbridgeProperty(source, "pos") || source;
    const models = readSoftbridgeProperty(pos, "models");
    const orderModel = readSoftbridgeProperty(models, "pos.order");
    const activeOrderUuids = collectSoftbridgeActiveOrderUuids(pos);
    return {
        sourceType: describeSoftbridgeObject(source),
        sourceKeys: listSoftbridgeObjectKeys(source),
        hasSourceCurrentOrder: Boolean(readSoftbridgeProperty(source, "currentOrder")),
        hasSourceSelectedOrder: Boolean(readSoftbridgeProperty(source, "selectedOrder")),
        posType: describeSoftbridgeObject(pos),
        posKeys: listSoftbridgeObjectKeys(pos),
        hasGetOrder: typeof readSoftbridgeProperty(pos, "getOrder") === "function",
        hasGetOrderLegacy: typeof readSoftbridgeProperty(pos, "get_order") === "function",
        hasOrderExportForPrinting:
            typeof readSoftbridgeProperty(pos, "orderExportForPrinting") === "function",
        activeOrderUuids,
        modelKeys: listSoftbridgeObjectKeys(models),
        orderModelKeys: listSoftbridgeObjectKeys(orderModel),
        hasOrderModelGetBy: typeof readSoftbridgeProperty(orderModel, "getBy") === "function",
        hasOrderModelFilter: typeof readSoftbridgeProperty(orderModel, "filter") === "function",
        openOrderCount: normalizeSoftbridgeOrderCollection(
            callSoftbridgeMethod(pos, "getOpenOrders") ||
                callSoftbridgeMethod(pos, "get_open_orders") ||
                readSoftbridgeProperty(pos, "orders") ||
                orderModel
        ).length,
    };
}

function describeSoftbridgeObject(value) {
    if (!value) {
        return "";
    }
    return value.constructor?.name || typeof value;
}

function listSoftbridgeObjectKeys(value) {
    if (!value || typeof value !== "object") {
        return [];
    }

    try {
        return Object.keys(value).slice(0, 40);
    } catch {
        return [];
    }
}

function readSoftbridgeProperty(target, fieldName) {
    if (!target || typeof target !== "object") {
        return null;
    }

    try {
        return target[fieldName];
    } catch (error) {
        console.warn(`[Softbridge][Bill] Could not read ${fieldName}.`, error);
        return null;
    }
}

function callSoftbridgeMethod(target, methodName, ...args) {
    const method = readSoftbridgeProperty(target, methodName);
    if (typeof method !== "function") {
        return null;
    }

    try {
        return method.apply(target, args);
    } catch (error) {
        console.warn(`[Softbridge][Bill] ${methodName}() failed during order resolution.`, error);
        return null;
    }
}

function normalizeSoftbridgeOrderlines(value) {
    if (!value) {
        return [];
    }

    if (Array.isArray(value)) {
        return value;
    }

    if (typeof value.toArray === "function") {
        return value.toArray() || [];
    }

    if (typeof value.getItems === "function") {
        return value.getItems() || [];
    }

    if (Array.isArray(value.models)) {
        return value.models;
    }

    if (Array.isArray(value.records)) {
        return value.records;
    }

    if (typeof value[Symbol.iterator] === "function") {
        return Array.from(value);
    }

    return [];
}

function getSoftbridgeOrderlines(order) {
    if (!order) {
        return [];
    }

    if (typeof readSoftbridgeProperty(order, "getOrderlines") === "function") {
        return normalizeSoftbridgeOrderlines(callSoftbridgeMethod(order, "getOrderlines"));
    }

    if (typeof readSoftbridgeProperty(order, "get_orderlines") === "function") {
        return normalizeSoftbridgeOrderlines(callSoftbridgeMethod(order, "get_orderlines"));
    }

    const orderlines = readSoftbridgeProperty(order, "orderlines");
    if (orderlines) {
        return normalizeSoftbridgeOrderlines(orderlines);
    }

    const lines = readSoftbridgeProperty(order, "lines");
    if (lines) {
        return normalizeSoftbridgeOrderlines(lines);
    }

    return [];
}

function getSoftbridgeOrderlineCount(order) {
    return getSoftbridgeOrderlines(order).length;
}

function buildSoftbridgeRestaurantBillReceiptData(pos, order) {
    const fallbackReceiptData = buildSoftbridgeReceiptDataFromOrder(pos, order);
    const exportedReceiptData = exportSoftbridgeReceiptDataFromPos(pos, order);
    const receiptData = {
        ...fallbackReceiptData,
        ...(exportedReceiptData || {}),
        isBill: true,
        show_change: false,
    };

    console.info("[Softbridge][Bill] Bill receipt data prepared.", {
        exportMethodAvailable: typeof readSoftbridgeProperty(pos, "orderExportForPrinting") === "function",
        usedExportedReceiptData: Boolean(exportedReceiptData),
        orderlineCount: Array.isArray(receiptData.orderlines)
            ? receiptData.orderlines.length
            : getSoftbridgeOrderlineCount(order),
        hasTaxTotals: Boolean(receiptData.taxTotals),
    });

    return receiptData;
}

async function buildSoftbridgeRestaurantBillBridgePayload({
    bridgeConfig,
    order,
    receiptData,
    receiptRoot,
    billHtml,
    billText,
}) {
    const receiptText = billText || buildSoftbridgeRestaurantBillTextFromData(receiptData);
    const receiptHtml = billHtml || getSoftbridgeElementHtml(receiptRoot);
    const structuredReceipt = await buildSoftbridgeRestaurantBillStructuredReceipt({
        order,
        receiptData,
        receiptRoot,
    });
    const preferHtml = bridgeConfig.payloadMode === "html" && receiptHtml;
    const payload = {
        order_name: order?.name || receiptData?.name || "",
        order_uid: getSoftbridgeOrderCandidateKey(order),
        source: RESTAURANT_BILL_SOURCE,
        print_type: "bill",
        document_type: "pro_forma_receipt",
        structured_receipt: structuredReceipt,
        capture_mode: preferHtml ? "direct_html" : "direct_text",
    };

    if (preferHtml) {
        payload.type = "receipt_html";
        payload.html = receiptHtml;
        if (receiptText) {
            payload.text = receiptText;
        }
    } else {
        payload.type = "receipt_text";
        payload.text = receiptText;
        if (receiptHtml) {
            payload.html = receiptHtml;
        }
    }

    applyRestaurantBillPayloadAdjustments(payload, {
        order,
        receiptData,
    });
    return payload;
}

async function buildSoftbridgeRestaurantBillStructuredReceipt({ order, receiptData, receiptRoot }) {
    const header = receiptData?.headerData || {};
    const company = header.company || {};
    const companyName =
        readSoftbridgeProperty(company, "name") ||
        readSoftbridgeProperty(company, "display_name") ||
        "";
    const cashier = String(header.cashier || getSoftbridgeOrderCashierName(order) || "").trim();
    const qrAsset = await extractSoftbridgeBillQrOrBarcodeAsset(receiptRoot);
    console.info("[Softbridge][Bill] QR/barcode lookup completed.", {
        found: Boolean(qrAsset?.image || qrAsset?.value),
        kind: qrAsset?.kind || "",
        hasImage: Boolean(qrAsset?.image),
        hasValue: Boolean(qrAsset?.value),
    });
    return {
        company_name: String(companyName || "").trim(),
        order_name: String(receiptData?.name || getSoftbridgeOrderDisplayName(order) || "").trim(),
        date: String(receiptData?.date || getSoftbridgeOrderDateLabel(order) || "").trim(),
        cashier,
        logo_image: null,
        qr_image: qrAsset?.kind !== "barcode" ? qrAsset?.image || null : null,
        qr_value: qrAsset?.kind !== "barcode" ? qrAsset?.value || "" : "",
        barcode_image: qrAsset?.kind === "barcode" ? qrAsset?.image || null : null,
        barcode_value: qrAsset?.kind === "barcode" ? qrAsset?.value || "" : "",
        signature_label: "",
        document_reference: resolveRestaurantBillReference(order, receiptData),
        document_title: "Pro forma receipt",
        lines: buildSoftbridgeRestaurantBillStructuredLines(receiptData, order),
        summary: buildSoftbridgeRestaurantBillStructuredSummary(receiptData, order),
        footer_lines: buildSoftbridgeRestaurantBillFooterLines(receiptData, order),
    };
}

function buildSoftbridgeRestaurantBillStructuredLines(receiptData, order) {
    const receiptLines = Array.isArray(receiptData?.orderlines) ? receiptData.orderlines : [];
    const rawOrderlines = getSoftbridgePrintableOrderlines(order);
    const sourceLength = rawOrderlines.length || receiptLines.length;
    const lines = [];

    for (let index = 0; index < sourceLength; index += 1) {
        const receiptLine = receiptLines[index] || {};
        const orderline = rawOrderlines[index] || null;
        const displayData = getSoftbridgeOrderlineDisplayData(orderline);
        const nameInfo = resolveSoftbridgeBillLineNameInfo(receiptLine, orderline, displayData);
        const quantity = resolveSoftbridgeBillLineQuantity(receiptLine, orderline, displayData);
        const amount = resolveSoftbridgeBillLineAmount(receiptLine, orderline, displayData, receiptData, order);
        const details = resolveSoftbridgeBillLineDetailLines(receiptLine, orderline, displayData, nameInfo.name);
        const note = resolveSoftbridgeBillLineNote(receiptLine, orderline, displayData);

        if (!nameInfo.name && !quantity && !amount && !details.length && !note) {
            continue;
        }

        const structuredLine = {
            qty: quantity || "1",
            name: nameInfo.name,
            amount,
            subline: "",
            details,
            note,
        };
        lines.push(structuredLine);
        console.info("[Softbridge][Bill] Order line prepared.", {
            index,
            productName: structuredLine.name,
            quantity: structuredLine.qty,
            price: structuredLine.amount,
            variantText: details.join(" | "),
        });
    }

    return lines;
}

function buildSoftbridgeRestaurantBillStructuredSummary(receiptData, order) {
    const summary = [];
    const taxTotals = receiptData?.taxTotals || {};
    const subtotals = Array.isArray(taxTotals.subtotals) ? taxTotals.subtotals : [];
    const firstSubtotal = subtotals[0] || {};
    const subtotalAmount = formatSoftbridgeBillMoneyWithCurrency(
        firstSubtotal.formatted_base_amount_currency ||
            firstSubtotal.formatted_amount ||
            firstSubtotal.base_amount_currency ||
            taxTotals.amount_untaxed ||
            taxTotals.base_amount ||
            "",
        receiptData,
        order
    );

    // Collect GCT for right side of subtotal
    const taxGroups = subtotals.flatMap((subtotal) =>
        Array.isArray(subtotal?.tax_groups) ? subtotal.tax_groups : []
    );
    const gctGroup = taxGroups.find((g) => /gct/i.test(String(g.group_name || "")));
    const otherTaxGroups = taxGroups.filter((g) => !/gct/i.test(String(g.group_name || "")));

    if (subtotalAmount) {
        summary.push({
            label: "Subtotal",
            amount: subtotalAmount,
            right_label: gctGroup ? String(gctGroup.group_name || "GCT").trim() : "",
            right_amount: gctGroup
                ? formatSoftbridgeBillMoneyWithCurrency(
                      gctGroup.formatted_tax_amount || gctGroup.tax_amount_currency || "",
                      receiptData,
                      order
                  )
                : "",
            emphasis: false,
            role: "subtotal",
        });
    }

    const totalAmount = formatSoftbridgeBillMoneyWithCurrency(
        taxTotals.formatted_order_total ||
            taxTotals.formatted_amount_total ||
            readSoftbridgeProperty(order, "currencyDisplayPriceIncl") ||
            taxTotals.order_total,
        receiptData,
        order
    );

    // Find other tax for right side of total
    const otherTaxGroup = otherTaxGroups[0] || {};
    if (totalAmount) {
        summary.push({
            label: String(receiptData?.label_total || "Total").trim(),
            amount: totalAmount,
            right_label: otherTaxGroup
                ? String(otherTaxGroup.group_name || "Tax").trim()
                : "",
            right_amount: otherTaxGroup
                ? formatSoftbridgeBillMoneyWithCurrency(
                      otherTaxGroup.formatted_tax_amount || otherTaxGroup.tax_amount_currency || "",
                      receiptData,
                      order
                  )
                : "",
            emphasis: true,
            role: "total_tax",
        });
    }

    // Payment method only - no amount for bills
    for (const paymentLine of resolveSoftbridgeBillPaymentLines(receiptData, order)) {
        summary.push({
            label: paymentLine.name,
            amount: "",
            emphasis: false,
            role: "payment",
        });
    }

    console.info("[Softbridge][Bill] Summary prepared.", {
        subtotal: subtotalAmount,
        total: totalAmount,
        paymentMethods: summary
            .filter((row) => row.role === "payment")
            .map((row) => row.label),
    });

    return summary;
}

function resolveSoftbridgeBillTaxSummary(subtotals = [], receiptData = {}, order = null) {
    const taxGroups = subtotals.flatMap((subtotal) =>
        Array.isArray(subtotal?.tax_groups) ? subtotal.tax_groups : []
    );
    const firstTaxGroup = taxGroups[0] || {};
    const rawTaxAmount =
        firstTaxGroup.formatted_tax_amount ||
        firstTaxGroup.tax_amount_currency ||
        firstTaxGroup.tax_amount ||
        receiptData?.tax_amount ||
        receiptData?.amount_tax ||
        "";
    const formattedTaxAmount = formatSoftbridgeBillMoneyWithCurrency(rawTaxAmount, receiptData, order);
    const rawLabel = String(firstTaxGroup.group_name || firstTaxGroup.name || "GCT").trim();
    const label = /gct/i.test(rawLabel) ? "GCT" : (rawLabel || "GCT");
    console.info("[Softbridge][Bill] Tax amount formatted.", {
        label,
        before: stringifySoftbridgeBillValue(rawTaxAmount),
        after: formattedTaxAmount,
    });
    return {
        label,
        amount: formattedTaxAmount,
    };
}

function resolveSoftbridgeBillPaymentLines(receiptData = {}, order = null) {
    const receiptPaymentLines = Array.isArray(receiptData?.paymentlines)
        ? receiptData.paymentlines
        : [];
    const orderPaymentLines = [
        normalizeSoftbridgeOrderlines(callSoftbridgeMethod(order, "getPaymentlines")),
        normalizeSoftbridgeOrderlines(callSoftbridgeMethod(order, "get_paymentlines")),
        normalizeSoftbridgeOrderlines(readSoftbridgeProperty(order, "payment_ids")),
        normalizeSoftbridgeOrderlines(readSoftbridgeProperty(order, "paymentlines")),
        normalizeSoftbridgeOrderlines(readSoftbridgeProperty(order, "paymentLines")),
    ].find((lines) => lines.length) || [];
    const rawPaymentLines = receiptPaymentLines.length
        ? receiptPaymentLines
        : orderPaymentLines;

    return rawPaymentLines
        .map((paymentLine) => {
            const paymentMethod =
                readSoftbridgeProperty(paymentLine, "payment_method_id") ||
                readSoftbridgeProperty(paymentLine, "payment_method") ||
                readSoftbridgeProperty(paymentLine, "paymentMethod") ||
                {};
            const name = stringifySoftbridgeBillValue(
                paymentLine.name ||
                    readSoftbridgeProperty(paymentMethod, "name") ||
                    readSoftbridgeProperty(paymentLine, "payment_method_name") ||
                    "Payment"
            );
            const amount = formatSoftbridgeBillMoneyWithCurrency(
                paymentLine.formatted_amount ||
                    readSoftbridgeProperty(paymentLine, "formatted_amount") ||
                    paymentLine.amount ||
                    readSoftbridgeProperty(paymentLine, "amount") ||
                    callSoftbridgeMethod(paymentLine, "getAmount") ||
                    callSoftbridgeMethod(paymentLine, "get_amount") ||
                    "",
                receiptData,
                order
            );
            return { name, amount };
        })
        .filter((line) => line.name && line.amount);
}

function buildSoftbridgeRestaurantBillFooterLines(receiptData, order) {
    const ticketNumber = resolveSoftbridgeTicketNumber(order, receiptData);
    const cashier = String(
        receiptData?.headerData?.cashier ||
            getSoftbridgeOrderCashierName(order) ||
            ""
    ).trim();

    return [
        ticketNumber ? `Ticket number: ${ticketNumber}` : "",
        cashier ? `Served by: ${cashier.replace(/^served by:\s*/i, "")}` : "",
    ]
        .map((value) => normalizeSoftbridgeRestaurantBillText(value))
        .filter(Boolean);
}

function buildSoftbridgeRestaurantBillTextFromData(receiptData) {
    const lines = [];
    const pushLine = (value = "") => {
        lines.push(normalizeSoftbridgeRestaurantBillText(value));
    };
    const company = receiptData?.headerData?.company || {};
    pushLine(readSoftbridgeProperty(company, "name") || "");
    pushLine(receiptData?.name || "");
    pushLine(receiptData?.date || "");
    pushLine(receiptData?.headerData?.cashier || "");
    pushLine(receiptData?.table ? `${receiptData.table}` : "");
    pushLine("Pro forma receipt");
    pushLine("");
    for (const line of receiptData?.orderlines || []) {
        const quantity = resolveSoftbridgeBillLineQuantity(line);
        const name = line.productName || line.product_name || line.full_product_name || line.name || "";
        const amount = formatSoftbridgeBillMoneyValue(
            line.price_display ||
            line.price ||
            line.price_with_tax ||
            line.price_subtotal_incl ||
            ""
        );
        pushLine([quantity, name, amount].filter(Boolean).join("  "));
        const unitPrice = formatSoftbridgeBillMoneyValue(
            line.unit_price ?? line.price_unit ?? line.unitPrice ?? ""
        );
        const unitName = stringifySoftbridgeBillValue(
            line.unit_name || line.uom_name || line.unit || line.unitName || line.unit_of_measure || ""
        );
        if (unitPrice) {
            pushLine(`  ${[unitPrice, unitName && !unitPrice.includes("/") ? `/ ${unitName}` : ""].filter(Boolean).join(" ")}`);
        }
        if (line.customerNote || line.customer_note) {
            pushLine(`Note: ${line.customerNote || line.customer_note}`);
        }
    }
    pushLine("");
    for (const row of buildSoftbridgeRestaurantBillStructuredSummary(receiptData, null)) {
        const left = `${row.label}: ${row.amount}`;
        const right = row.right_label && row.right_amount ? `${row.right_label}: ${row.right_amount}` : "";
        pushLine([left, right].filter(Boolean).join("        "));
    }
    const bottomReferenceLines = buildSoftbridgeRestaurantBillFooterLines(receiptData, null);
    if (bottomReferenceLines.length) {
        pushLine("");
    }
    for (const footerLine of bottomReferenceLines) {
        pushLine(footerLine);
    }
    return lines.filter(Boolean).join("\n");
}

function stringifySoftbridgeBillValue(value) {
    if (value === undefined || value === null) {
        return "";
    }
    return String(value).trim();
}

function getSoftbridgePrintableOrderlines(order) {
    const rawOrderlines = getSoftbridgeOrderlines(order);
    const topLevelLines = rawOrderlines.filter((line) => !isSoftbridgeComboChildOrderline(line));
    return topLevelLines.length ? topLevelLines : rawOrderlines;
}

function isSoftbridgeComboChildOrderline(line) {
    if (!line || typeof line !== "object") {
        return false;
    }
    return Boolean(
        readSoftbridgeProperty(line, "combo_parent_id") ||
            readSoftbridgeProperty(line, "comboParent") ||
            getSoftbridgeOrderlineDisplayData(line)?.comboParent
    );
}

function getSoftbridgeOrderlineDisplayData(orderline) {
    if (!orderline) {
        return {};
    }
    return (
        callSoftbridgeMethod(orderline, "getDisplayData") ||
        callSoftbridgeMethod(orderline, "get_display_data") ||
        readSoftbridgeProperty(orderline, "displayData") ||
        {}
    );
}

function resolveSoftbridgeBillLineQuantity(line = {}, orderline = null, displayData = {}) {
    const quantityString = callSoftbridgeMethod(orderline, "getQuantityStr");
    const normalizedQuantityString = normalizeSoftbridgeQuantityString(quantityString);
    const quantity = [
        displayData.qty,
        displayData.quantity,
        normalizedQuantityString,
        line.qty,
        line.quantity,
        line.qty_str,
        line.quantityStr,
        line.quantity_str,
        line.qty_display,
        line.quantity_display,
    ]
        .map((value) => stringifySoftbridgeBillValue(value))
        .find(Boolean);

    return quantity || "1";
}

function normalizeSoftbridgeQuantityString(quantityString) {
    if (quantityString === undefined || quantityString === null) {
        return "";
    }
    if (typeof quantityString === "object") {
        return [
            readSoftbridgeProperty(quantityString, "qtyStr"),
            [
                readSoftbridgeProperty(quantityString, "integerPart"),
                readSoftbridgeProperty(quantityString, "unitPart"),
                readSoftbridgeProperty(quantityString, "decimalPoint"),
                readSoftbridgeProperty(quantityString, "decimalPart"),
            ]
                .filter((value) => value !== null && value !== undefined && value !== "")
                .join(""),
        ]
            .map((value) => stringifySoftbridgeBillValue(value))
            .find(Boolean) || "";
    }
    return stringifySoftbridgeBillValue(quantityString);
}

function resolveSoftbridgeBillLineNameInfo(receiptLine = {}, orderline = null, displayData = {}) {
    const displayName = readSoftbridgeProperty(orderline, "orderDisplayProductName") || {};
    const product = callSoftbridgeMethod(orderline, "getProduct") ||
        callSoftbridgeMethod(orderline, "get_product") ||
        readSoftbridgeProperty(orderline, "product_id") ||
        {};
    const rawProductName = [
        readSoftbridgeProperty(displayName, "name"),
        readSoftbridgeProperty(product, "display_name"),
        readSoftbridgeProperty(product, "name"),
        receiptLine.productName,
        receiptLine.product_name,
        receiptLine.name,
        displayData.productName,
        readSoftbridgeProperty(orderline, "full_product_name"),
        callSoftbridgeMethod(orderline, "getFullProductName"),
        callSoftbridgeMethod(orderline, "get_full_product_name"),
        readSoftbridgeProperty(orderline, "name"),
    ]
        .map((value) => stringifySoftbridgeBillValue(value))
        .find(Boolean);
    const attributeText = resolveSoftbridgeOrderlineAttributeText(orderline, displayData);
    const fallbackFullName = [
        receiptLine.full_product_name,
        displayData.productName,
        readSoftbridgeProperty(orderline, "full_product_name"),
        callSoftbridgeMethod(orderline, "getFullProductName"),
        callSoftbridgeMethod(orderline, "get_full_product_name"),
    ]
        .map((value) => stringifySoftbridgeBillValue(value))
        .find(Boolean);
    const strippedName = stripSoftbridgeLineAttributeSuffix(rawProductName, attributeText);

    return {
        name: strippedName || rawProductName || fallbackFullName || "",
        fullName: fallbackFullName || rawProductName || "",
        attributeText,
    };
}

function stripSoftbridgeLineAttributeSuffix(productName, attributeText) {
    const name = stringifySoftbridgeBillValue(productName);
    const attributes = stringifySoftbridgeBillValue(attributeText);
    if (!name || !attributes) {
        return name;
    }
    const suffix = `(${attributes})`;
    return name.endsWith(suffix) ? name.slice(0, -suffix.length).trim() : name;
}

function resolveSoftbridgeOrderlineAttributeText(orderline, displayData = {}) {
    const displayName = readSoftbridgeProperty(orderline, "orderDisplayProductName") || {};
    const displayDataName = readSoftbridgeProperty(displayData, "orderDisplayProductName") || {};
    const candidates = [
        readSoftbridgeProperty(displayName, "attributeString"),
        readSoftbridgeProperty(displayName, "attribute_string"),
        readSoftbridgeProperty(displayName, "attributes"),
        readSoftbridgeProperty(displayDataName, "attributeString"),
        readSoftbridgeProperty(displayDataName, "attribute_string"),
        readSoftbridgeProperty(displayDataName, "attributes"),
        displayData.attributeString,
        displayData.attribute_string,
        displayData.attributes,
    ]
        .map((value) => stringifySoftbridgeBillValue(value))
        .filter(Boolean);
    if (candidates.length) {
        return candidates[0];
    }

    const attributeLines = buildSoftbridgeAttributeTextFromOrderline(orderline);
    return attributeLines.join(", ");
}

function buildSoftbridgeAttributeTextFromOrderline(orderline) {
    const attributeValues = normalizeSoftbridgeOrderlines(
        readSoftbridgeProperty(orderline, "attribute_value_ids") ||
            readSoftbridgeProperty(orderline, "attributeValueIds")
    );
    const customValues = normalizeSoftbridgeOrderlines(
        readSoftbridgeProperty(orderline, "custom_attribute_value_ids") ||
            readSoftbridgeProperty(orderline, "customAttributeValueIds")
    );
    const values = [];

    for (const attributeValue of attributeValues) {
        const valueName = stringifySoftbridgeBillValue(
            readSoftbridgeProperty(attributeValue, "name") ||
                readSoftbridgeProperty(attributeValue, "display_name") ||
                attributeValue
        );
        if (!valueName) {
            continue;
        }

        if (readSoftbridgeProperty(attributeValue, "is_custom")) {
            const customValue = findSoftbridgeCustomAttributeValue(attributeValue, customValues);
            if (customValue) {
                const attributeName = stringifySoftbridgeBillValue(
                    readSoftbridgeProperty(readSoftbridgeProperty(attributeValue, "attribute_id"), "name")
                );
                values.push([attributeName, valueName, customValue].filter(Boolean).join(": "));
                continue;
            }
        }
        values.push(valueName);
    }

    if (values.length) {
        return values;
    }

    const product = callSoftbridgeMethod(orderline, "getProduct") ||
        callSoftbridgeMethod(orderline, "get_product") ||
        readSoftbridgeProperty(orderline, "product_id") ||
        {};
    const templateVariantValues = normalizeSoftbridgeOrderlines(
        readSoftbridgeProperty(product, "product_template_variant_value_ids") ||
            readSoftbridgeProperty(product, "productTemplateVariantValueIds")
    );
    return templateVariantValues
        .map((value) => stringifySoftbridgeBillValue(
            readSoftbridgeProperty(value, "name") ||
                readSoftbridgeProperty(value, "display_name") ||
                value
        ))
        .filter(Boolean);
}

function findSoftbridgeCustomAttributeValue(attributeValue, customValues = []) {
    const attributeValueId = String(readSoftbridgeProperty(attributeValue, "id") || "");
    for (const customValue of customValues) {
        const linkedValue = readSoftbridgeProperty(customValue, "custom_product_template_attribute_value_id") ||
            readSoftbridgeProperty(customValue, "attribute_value_id") ||
            {};
        const linkedId = String(readSoftbridgeProperty(linkedValue, "id") || linkedValue || "");
        if (attributeValueId && linkedId && attributeValueId === linkedId) {
            return stringifySoftbridgeBillValue(
                readSoftbridgeProperty(customValue, "custom_value") ||
                    readSoftbridgeProperty(customValue, "value")
            );
        }
    }
    return "";
}

function resolveSoftbridgeBillLineDetailLines(receiptLine = {}, orderline = null, displayData = {}, productName = "") {
    const details = [];
    const pushDetail = (value) => {
        const normalized = normalizeSoftbridgeBillDetailText(value, productName);
        if (normalized && !details.some((existing) => existing.toLowerCase() === normalized.toLowerCase())) {
            details.push(normalized);
        }
    };

    pushDetail(resolveSoftbridgeOrderlineAttributeText(orderline, displayData));
    for (const comboText of resolveSoftbridgeOrderlineComboDetailLines(orderline)) {
        pushDetail(comboText);
    }
    for (const packLotLine of normalizeSoftbridgeOrderlines(displayData.packLotLines || receiptLine.packLotLines)) {
        pushDetail(packLotLine);
    }
    pushDetail(receiptLine.variantText || receiptLine.variant_text || receiptLine.attributeString || receiptLine.attribute_string);

    return details;
}

function normalizeSoftbridgeBillDetailText(value, productName = "") {
    const normalized = normalizeSoftbridgeRestaurantBillText(value)
        .replace(/^[-.\s]+/, "")
        .replace(/\s+/g, " ")
        .trim();
    if (!normalized) {
        return "";
    }
    const name = normalizeSoftbridgeRestaurantBillText(productName).toLowerCase();
    if (name && normalized.toLowerCase() === name) {
        return "";
    }
    return normalized;
}

function resolveSoftbridgeOrderlineComboDetailLines(orderline) {
    const comboLines = normalizeSoftbridgeOrderlines(
        readSoftbridgeProperty(orderline, "combo_line_ids") ||
            readSoftbridgeProperty(orderline, "comboLineIds")
    );
    const names = comboLines
        .filter((line) => line && typeof line === "object")
        .map((line) => {
            const displayData = getSoftbridgeOrderlineDisplayData(line);
            const product = callSoftbridgeMethod(line, "getProduct") ||
                callSoftbridgeMethod(line, "get_product") ||
                readSoftbridgeProperty(line, "product_id") ||
                {};
            return stringifySoftbridgeBillValue(
                displayData.productName ||
                    readSoftbridgeProperty(line, "full_product_name") ||
                    callSoftbridgeMethod(line, "getFullProductName") ||
                    callSoftbridgeMethod(line, "get_full_product_name") ||
                    readSoftbridgeProperty(product, "display_name") ||
                    readSoftbridgeProperty(product, "name") ||
                    readSoftbridgeProperty(line, "name")
            );
        })
        .filter(Boolean);

    return names.length ? [names.join(", ")] : [];
}

function resolveSoftbridgeBillLineNote(receiptLine = {}, orderline = null, displayData = {}) {
    return stringifySoftbridgeBillValue(
        displayData.customerNote ||
            receiptLine.customerNote ||
            receiptLine.customer_note ||
            callSoftbridgeMethod(orderline, "getCustomerNote") ||
            callSoftbridgeMethod(orderline, "get_customer_note") ||
            readSoftbridgeProperty(orderline, "customer_note") ||
            ""
    );
}

function resolveSoftbridgeBillLineAmount(receiptLine = {}, orderline = null, displayData = {}, receiptData = {}, order = null) {
    const rawAmount = [
        receiptLine.price_display,
        receiptLine.price,
        receiptLine.price_with_tax,
        receiptLine.price_subtotal_incl,
        displayData.price,
        readSoftbridgeProperty(orderline, "currencyDisplayPrice"),
        readSoftbridgeProperty(orderline, "currencyDisplayPriceIncl"),
        callSoftbridgeMethod(orderline, "getPriceString"),
        callSoftbridgeMethod(orderline, "get_price_string"),
        callSoftbridgeMethod(orderline, "getComboTotalPrice"),
        callSoftbridgeMethod(orderline, "getComboTotalPriceWithoutTax"),
        callSoftbridgeMethod(orderline, "get_display_price"),
        callSoftbridgeMethod(orderline, "getDisplayPrice"),
        readSoftbridgeProperty(orderline, "price_subtotal_incl"),
    ]
        .map((value) => stringifySoftbridgeBillValue(value))
        .find((value) => value && !/^free$/i.test(value));

    return formatSoftbridgeBillMoneyWithCurrency(rawAmount, receiptData, order);
}

function formatSoftbridgeBillMoneyValue(value) {
    if (value === undefined || value === null || value === "") {
        return "";
    }

    if (typeof value === "number") {
        return Number.isFinite(value) ? value.toFixed(2) : "";
    }

    const text = String(value).trim();
    if (!text) {
        return "";
    }

    const match = text.match(/-?\d[\d,]*(?:\.\d+)?/);
    if (!match) {
        return text;
    }

    const numericText = match[0];
    const numericValue = Number(numericText.replace(/,/g, ""));
    if (!Number.isFinite(numericValue)) {
        return text;
    }

    const formattedNumber = numericValue.toLocaleString("en-US", {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
        useGrouping: true,
    });
    return `${text.slice(0, match.index)}${formattedNumber}${text.slice((match.index || 0) + numericText.length)}`;
}

function formatSoftbridgeBillMoneyWithCurrency(value, receiptData = {}, order = null) {
    const formattedValue = formatSoftbridgeBillMoneyValue(value);
    if (!formattedValue) {
        return "";
    }
    if (/[^\d\s,.\-]/.test(formattedValue)) {
        return formattedValue;
    }

    const currencyInfo = resolveSoftbridgeBillCurrencyInfo(receiptData, order);
    if (!currencyInfo.symbol) {
        return formattedValue;
    }
    return currencyInfo.position === "before"
        ? `${currencyInfo.symbol} ${formattedValue}`
        : `${formattedValue} ${currencyInfo.symbol}`;
}

function resolveSoftbridgeBillCurrencyInfo(receiptData = {}, order = null) {
    const currency = receiptData?.currency ||
        receiptData?.headerData?.currency ||
        readSoftbridgeProperty(order, "currency") ||
        readSoftbridgeProperty(order, "currency_id") ||
        readSoftbridgeProperty(readSoftbridgeProperty(order, "config"), "currency_id") ||
        {};
    const symbol = stringifySoftbridgeBillValue(
        readSoftbridgeProperty(currency, "symbol") ||
            readSoftbridgeProperty(currency, "name") ||
            receiptData?.currency_symbol ||
            receiptData?.currencySymbol ||
            ""
    );
    const position = stringifySoftbridgeBillValue(
        readSoftbridgeProperty(currency, "position") ||
            receiptData?.currency_position ||
            receiptData?.currencyPosition ||
            ""
    ).toLowerCase();

    return {
        symbol,
        position: position === "before" ? "before" : "after",
    };
}

async function extractSoftbridgeBillQrOrBarcodeAsset(receiptRoot) {
    if (!receiptRoot?.querySelectorAll) {
        return null;
    }

    const candidate = findSoftbridgeBillQrOrBarcodeNode(receiptRoot);
    if (!candidate?.node) {
        return null;
    }

    const value = extractSoftbridgeBillQrOrBarcodeValue(candidate.node);
    const image = await convertSoftbridgeBillVisualNodeToPng(candidate.node, {
        minSize: candidate.kind === "barcode" ? 320 : 256,
        square: candidate.kind !== "barcode",
    });

    return {
        kind: candidate.kind,
        value,
        image,
    };
}

function findSoftbridgeBillQrOrBarcodeNode(receiptRoot) {
    const receiptBounds = receiptRoot.getBoundingClientRect?.() || {};
    const receiptHeight = Math.max(1, receiptBounds.height || receiptRoot.scrollHeight || 0);
    const nodes = Array.from(receiptRoot.querySelectorAll("img, canvas, svg"));
    const candidates = nodes
        .map((node) => {
            const bounds = node.getBoundingClientRect?.() || {};
            const width =
                bounds.width ||
                node.clientWidth ||
                node.naturalWidth ||
                node.width?.baseVal?.value ||
                node.width ||
                0;
            const height =
                bounds.height ||
                node.clientHeight ||
                node.naturalHeight ||
                node.height?.baseVal?.value ||
                node.height ||
                0;
            const top = bounds.top != null && receiptBounds.top != null ? bounds.top - receiptBounds.top : 0;
            const descriptor = [
                node.id,
                node.className?.baseVal || node.className,
                node.getAttribute?.("alt"),
                node.getAttribute?.("title"),
                node.getAttribute?.("aria-label"),
                node.getAttribute?.("src"),
                node.getAttribute?.("data-src"),
                node.getAttribute?.("data-barcode"),
                node.getAttribute?.("data-qr"),
            ]
                .map((value) => String(value || "").toLowerCase())
                .join(" ");
            const isExplicitBarcode = /\b(barcode|bar-code|ean|code128|code-128)\b/.test(descriptor);
            const isExplicitQr = /\b(qr|qrcode|qr-code)\b/.test(descriptor);
            const ratio = height > 0 ? width / height : 0;
            const isSquare = ratio >= 0.72 && ratio <= 1.28;
            const isUsefulSize = width >= 28 && height >= 28;
            const isLikelyQr = isUsefulSize && isSquare && top >= receiptHeight * 0.18;
            const isLikelyBarcode = isUsefulSize && ratio >= 1.8 && height >= 24;
            const kind = isExplicitBarcode || (!isExplicitQr && isLikelyBarcode) ? "barcode" : "qr";
            const score =
                (isExplicitQr || isExplicitBarcode ? 1000 : 0) +
                (isLikelyQr || isLikelyBarcode ? 500 : 0) +
                Math.round(width * height) +
                Math.round(top);
            return {
                node,
                kind,
                width,
                height,
                isCandidate: isExplicitQr || isExplicitBarcode || isLikelyQr || isLikelyBarcode,
                score,
            };
        })
        .filter((candidate) => candidate.isCandidate)
        .sort((left, right) => right.score - left.score);

    return candidates[0] || null;
}

function extractSoftbridgeBillQrOrBarcodeValue(node) {
    const attributes = [
        "data-qr",
        "data-qrcode",
        "data-barcode",
        "data-value",
        "barcode",
        "alt",
        "title",
        "aria-label",
        "src",
        "href",
    ];

    for (const attributeName of attributes) {
        const rawValue = node.getAttribute?.(attributeName);
        const value = decodeSoftbridgeBillBarcodeValue(rawValue);
        if (value) {
            return value;
        }
    }

    return "";
}

function decodeSoftbridgeBillBarcodeValue(rawValue) {
    const value = String(rawValue || "").trim();
    if (!value) {
        return "";
    }

    const barcodePathMatch = value.match(/\/report\/barcode\/(?:QR|Code128|EAN13|EAN8)\/([^?#]+)/i);
    if (barcodePathMatch?.[1]) {
        return safeDecodeSoftbridgeBillURIComponent(barcodePathMatch[1]);
    }

    const queryMatch = value.match(/[?&](?:value|barcode|qr|data)=([^&#]+)/i);
    if (queryMatch?.[1]) {
        return safeDecodeSoftbridgeBillURIComponent(queryMatch[1]);
    }

    if (/^(?:data:image|blob:|https?:|\/web\/|\/point_of_sale\/|\/report\/barcode\/)/i.test(value)) {
        return "";
    }

    return value.length >= 6 ? value : "";
}

function safeDecodeSoftbridgeBillURIComponent(value) {
    try {
        return decodeURIComponent(String(value || "").replace(/\+/g, " ")).trim();
    } catch {
        return String(value || "").trim();
    }
}

async function convertSoftbridgeBillVisualNodeToPng(node, { minSize = 256, square = true } = {}) {
    try {
        if (node instanceof HTMLCanvasElement) {
            return renderSoftbridgeBillImageSourceToPng(node, {
                sourceWidth: node.width,
                sourceHeight: node.height,
                minSize,
                square,
            });
        }

        if (node instanceof SVGElement) {
            const svgMarkup = new XMLSerializer().serializeToString(node);
            const source = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svgMarkup)}`;
            const image = await loadSoftbridgeBillImage(source);
            return renderSoftbridgeBillImageSourceToPng(image, {
                sourceWidth: image.naturalWidth || node.getBoundingClientRect?.().width || minSize,
                sourceHeight: image.naturalHeight || node.getBoundingClientRect?.().height || minSize,
                minSize,
                square,
            });
        }

        if (node instanceof HTMLImageElement) {
            const source = node.currentSrc || node.src || node.getAttribute("src") || "";
            if (!source) {
                return null;
            }
            const dataUrl = await resolveSoftbridgeBillImageSourceDataUrl(source);
            const image = await loadSoftbridgeBillImage(dataUrl || source);
            return renderSoftbridgeBillImageSourceToPng(image, {
                sourceWidth: image.naturalWidth || node.naturalWidth || node.clientWidth || minSize,
                sourceHeight: image.naturalHeight || node.naturalHeight || node.clientHeight || minSize,
                minSize,
                square,
            });
        }
    } catch (error) {
        console.warn("[Softbridge][Bill] QR/barcode image conversion failed.", error);
    }

    return null;
}

async function resolveSoftbridgeBillImageSourceDataUrl(source) {
    if (!source || source.startsWith("data:")) {
        return source || "";
    }

    const response = await fetch(source, { mode: "cors", credentials: "include" });
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

async function loadSoftbridgeBillImage(source) {
    return await new Promise((resolve, reject) => {
        const image = new Image();
        image.decoding = "sync";
        image.onload = () => resolve(image);
        image.onerror = () => reject(new Error("Image could not be loaded."));
        image.src = source;
    });
}

function renderSoftbridgeBillImageSourceToPng(source, {
    sourceWidth = 0,
    sourceHeight = 0,
    minSize = 256,
    square = true,
} = {}) {
    const width = Math.max(1, Math.round(sourceWidth || source.width || minSize));
    const height = Math.max(1, Math.round(sourceHeight || source.height || minSize));
    const scale = Math.max(1, Math.ceil(minSize / Math.max(width, height)));
    const canvasWidth = square ? Math.max(minSize, width * scale, height * scale) : Math.max(minSize, width * scale);
    const canvasHeight = square ? canvasWidth : Math.max(96, height * scale);
    const drawWidth = square ? Math.min(canvasWidth, width * scale) : canvasWidth;
    const drawHeight = square
        ? Math.min(canvasHeight, height * scale)
        : Math.max(1, Math.round(height * (drawWidth / width)));
    const canvas = document.createElement("canvas");
    canvas.width = canvasWidth;
    canvas.height = canvasHeight;
    const context = canvas.getContext("2d");
    if (!context) {
        return null;
    }
    context.imageSmoothingEnabled = false;
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.drawImage(
        source,
        Math.floor((canvasWidth - drawWidth) / 2),
        Math.floor((canvasHeight - drawHeight) / 2),
        drawWidth,
        drawHeight
    );
    return canvas.toDataURL("image/png");
}

function exportSoftbridgeReceiptDataFromPos(pos, order) {
    const exporter = readSoftbridgeProperty(pos, "orderExportForPrinting");
    if (typeof exporter !== "function") {
        console.info(
            "[Softbridge][Bill] POS order export method is unavailable; using Odoo order object and rendered receipt DOM instead."
        );
        return null;
    }

    try {
        return exporter.call(pos, order) || null;
    } catch (error) {
        console.warn(
            "[Softbridge][Bill] POS order export failed; using Odoo order object and rendered receipt DOM instead.",
            error
        );
        return null;
    }
}

function buildSoftbridgeReceiptDataFromOrder(pos, order) {
    const config = readSoftbridgeProperty(pos, "config") || readSoftbridgeProperty(order, "config") || {};
    const company = readSoftbridgeProperty(order, "company") || readSoftbridgeProperty(pos, "company") || {};
    const taxTotals = buildSoftbridgeTaxTotalsFromOrder(order);
    return {
        name: getSoftbridgeOrderDisplayName(order),
        date: getSoftbridgeOrderDateLabel(order),
        ticket_code: readSoftbridgeProperty(order, "ticket_code") || "",
        trackingNumber:
            readSoftbridgeProperty(order, "trackingNumber") ||
            readSoftbridgeProperty(order, "tracking_number") ||
            "",
        table: getSoftbridgeOrderTableLabel(order),
        headerData: {
            company,
            cashier: getSoftbridgeOrderCashierName(order),
            header: readSoftbridgeProperty(config, "receipt_header") || "",
            table: getSoftbridgeOrderTableLabel(order),
        },
        orderlines: getSoftbridgePrintableOrderlines(order).map(buildSoftbridgeReceiptLineDataFromOrderline),
        paymentlines: normalizeSoftbridgeOrderlines(readSoftbridgeProperty(order, "payment_ids")).map(
            buildSoftbridgePaymentLineDataFromOrderPayment
        ),
        taxTotals,
        footer: readSoftbridgeProperty(config, "receipt_footer") || "",
        label_total: "Total",
        label_change: "Change",
        label_rounding: "Rounding",
        label_discounts: "Discounts",
    };
}

function buildSoftbridgeTaxTotalsFromOrder(order) {
    const taxDetails = readSoftbridgeProperty(readSoftbridgeProperty(order, "prices"), "taxDetails") || {};
    return {
        ...taxDetails,
        formatted_order_total:
            readSoftbridgeProperty(taxDetails, "formatted_order_total") ||
            readSoftbridgeProperty(order, "currencyDisplayPriceIncl") ||
            "",
        formatted_amount_total:
            readSoftbridgeProperty(taxDetails, "formatted_amount_total") ||
            readSoftbridgeProperty(order, "currencyDisplayPriceIncl") ||
            "",
        order_total:
            readSoftbridgeProperty(taxDetails, "order_total") ??
            readSoftbridgeProperty(order, "priceIncl") ??
            readSoftbridgeProperty(order, "amount_total") ??
            "",
        order_sign: readSoftbridgeProperty(taxDetails, "order_sign") || 1,
    };
}

function buildSoftbridgeReceiptLineDataFromOrderline(line) {
    const product =
        callSoftbridgeMethod(line, "getProduct") ||
        readSoftbridgeProperty(line, "product_id") ||
        {};
    const displayName = readSoftbridgeProperty(line, "orderDisplayProductName") || {};
    const displayData = getSoftbridgeOrderlineDisplayData(line);
    const nameInfo = resolveSoftbridgeBillLineNameInfo({}, line, displayData);
    const quantityString = callSoftbridgeMethod(line, "getQuantityStr");
    const quantity =
        normalizeSoftbridgeQuantityString(quantityString);

    return {
        qty: quantity || readSoftbridgeProperty(line, "qty") || readSoftbridgeProperty(line, "quantity") || "",
        productName:
            nameInfo.name ||
            readSoftbridgeProperty(displayName, "fullProductName") ||
            readSoftbridgeProperty(displayName, "name") ||
            readSoftbridgeProperty(product, "display_name") ||
            readSoftbridgeProperty(product, "name") ||
            readSoftbridgeProperty(line, "full_product_name") ||
            readSoftbridgeProperty(line, "name") ||
            "",
        price_display:
            readSoftbridgeProperty(line, "currencyDisplayPrice") ||
            readSoftbridgeProperty(line, "currencyDisplayPriceIncl") ||
            readSoftbridgeProperty(line, "price_display") ||
            readSoftbridgeProperty(line, "price_subtotal_incl") ||
            "",
        unit_price:
            readSoftbridgeProperty(line, "currencyDisplayPriceUnit") ||
            readSoftbridgeProperty(line, "price_unit") ||
            "",
        customerNote:
            readSoftbridgeProperty(line, "customerNote") ||
            callSoftbridgeMethod(line, "getCustomerNote") ||
            "",
        variantText: nameInfo.attributeText,
    };
}

function buildSoftbridgePaymentLineDataFromOrderPayment(paymentLine) {
    const paymentMethod = readSoftbridgeProperty(paymentLine, "payment_method_id") || {};
    return {
        name: readSoftbridgeProperty(paymentMethod, "name") || readSoftbridgeProperty(paymentLine, "name") || "Payment",
        amount:
            readSoftbridgeProperty(paymentLine, "formatted_amount") ||
            readSoftbridgeProperty(paymentLine, "amount") ||
            callSoftbridgeMethod(paymentLine, "getAmount") ||
            "",
    };
}

function getSoftbridgeOrderDateLabel(order) {
    for (const methodName of ["formatDateOrTime", "getDate", "get_date"]) {
        const method = readSoftbridgeProperty(order, methodName);
        if (typeof method !== "function") {
            continue;
        }
        try {
            const value = methodName === "formatDateOrTime"
                ? method.call(order, "date_order")
                : method.call(order);
            if (value) {
                return String(value);
            }
        } catch {
            // Some Odoo builds require a different date formatter signature.
        }
    }
    return String(readSoftbridgeProperty(order, "date_order") || readSoftbridgeProperty(order, "creation_date") || "");
}

function getSoftbridgeOrderCashierName(order) {
    return String(
        callSoftbridgeMethod(order, "getCashierName") ||
            readSoftbridgeProperty(readSoftbridgeProperty(order, "user_id"), "name") ||
            readSoftbridgeProperty(readSoftbridgeProperty(order, "employee_id"), "name") ||
            ""
    ).trim();
}

function getSoftbridgeOrderTableLabel(order) {
    const table =
        callSoftbridgeMethod(order, "getTable") ||
        readSoftbridgeProperty(order, "table") ||
        readSoftbridgeProperty(order, "table_id") ||
        null;
    if (!table) {
        return "";
    }
    return String(
        readSoftbridgeProperty(table, "name") ||
            readSoftbridgeProperty(table, "label") ||
            readSoftbridgeProperty(table, "table_number") ||
            readSoftbridgeProperty(table, "number") ||
            readSoftbridgeProperty(table, "id") ||
            ""
    ).trim();
}

function applyRestaurantBillPayloadAdjustments(
    payload,
    { order, receiptData } = {}
) {
    const structuredReceipt = payload?.structured_receipt;
    if (!structuredReceipt) {
        return;
    }

    structuredReceipt.document_title = "Pro forma receipt";
    structuredReceipt.document_reference = resolveRestaurantBillReference(order, receiptData);
    structuredReceipt.signature_label = "";

    const cashier = String(structuredReceipt.cashier || "").trim();
    if (cashier && !/^served by:/i.test(cashier)) {
        structuredReceipt.cashier = `Served by: ${cashier}`;
    }
    structuredReceipt.footer_lines = buildSoftbridgeRestaurantBillFooterLines(receiptData, order);
}

function resolveRestaurantBillReference(order, receiptData) {
    const candidates = [
        receiptData?.table,
        receiptData?.table_name,
        receiptData?.headerData?.table,
        order?.table?.name,
        order?.getTable?.()?.name,
        order?.getTable?.()?.number,
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
            const normalizedObjectValue = String(objectValue || "").trim();
            if (normalizedObjectValue) {
                return normalizedObjectValue;
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

function buildSoftbridgeConfigFromPosConfig(config = {}) {
    return {
        enabled: Boolean(config.softbridge_enabled),
        baseUrl: (config.softbridge_bridge_url || DEFAULT_BRIDGE_URL).trim(),
        apiToken: (config.softbridge_api_token || "").trim(),
        autoSendReceipt: Boolean(config.softbridge_auto_send_receipt),
        manualButton: Boolean(config.softbridge_manual_button),
        payloadMode: config.softbridge_payload_mode || "text",
        timeoutMs: Number(config.softbridge_request_timeout_ms) || DEFAULT_TIMEOUT_MS,
    };
}

function buildSoftbridgePathEndpoint(bridgeConfig, targetPath) {
    const normalizedBaseUrl = (bridgeConfig.baseUrl || DEFAULT_BRIDGE_URL).replace(/\/+$/, "");
    const knownPaths = [BRIDGE_RECEIPT_PATH, BRIDGE_STATUS_PATH];
    const rootUrl = knownPaths.reduce((baseUrl, knownPath) => {
        return baseUrl.endsWith(knownPath)
            ? baseUrl.slice(0, -knownPath.length)
            : baseUrl;
    }, normalizedBaseUrl);

    return `${rootUrl}${targetPath}`;
}

async function probeSoftbridgeDesktopBridge(bridgeConfig, options = {}) {
    if (!bridgeConfig.enabled) {
        return null;
    }

    const force = Boolean(options.force);
    const now = Date.now();
    if (
        !force &&
        SOFTBRIDGE_RUNTIME.lastBridgeProbeOkAt &&
        now - SOFTBRIDGE_RUNTIME.lastBridgeProbeOkAt < BRIDGE_PROBE_CACHE_MS
    ) {
        return { status: "running", cached: true };
    }

    if (!force && SOFTBRIDGE_RUNTIME.bridgeProbeInFlight) {
        return await SOFTBRIDGE_RUNTIME.bridgeProbeInFlight;
    }

    const endpoint = buildSoftbridgePathEndpoint(bridgeConfig, BRIDGE_STATUS_PATH);
    const abortController = new AbortController();
    const timeoutHandle = window.setTimeout(() => {
        abortController.abort();
    }, Math.min(bridgeConfig.timeoutMs, BRIDGE_PROBE_TIMEOUT_MS));

    const probePromise = (async () => {
        try {
            let response;
            try {
                response = await fetch(endpoint, {
                    method: "GET",
                    mode: "cors",
                    credentials: "omit",
                    cache: "no-store",
                    signal: abortController.signal,
                });
            } catch (error) {
                if (error && error.name === "AbortError") {
                    throw new Error(
                        `Softbridge desktop bridge did not answer at ${endpoint} within ${Math.min(
                            bridgeConfig.timeoutMs,
                            BRIDGE_PROBE_TIMEOUT_MS
                        )} ms.`
                    );
                }
                throw new Error(
                    `Softbridge desktop bridge is not reachable at ${endpoint}. Make sure the Windows app is open and the server state is Running.`
                );
            }

            const rawBody = await response.text();
            const parsedBody = rawBody ? safeParseSoftbridgeJson(rawBody) : {};
            if (!response.ok || parsedBody?.status !== "running") {
                const message =
                    parsedBody?.message ||
                    parsedBody?.error ||
                    `Softbridge bridge status check failed with ${response.status}.`;
                throw new Error(message);
            }

            SOFTBRIDGE_RUNTIME.lastBridgeProbeOkAt = Date.now();
            return parsedBody;
        } finally {
            window.clearTimeout(timeoutHandle);
        }
    })();

    SOFTBRIDGE_RUNTIME.bridgeProbeInFlight = probePromise;
    SOFTBRIDGE_RUNTIME.lastBridgeProbeStartedAt = now;
    try {
        return await probePromise;
    } finally {
        if (SOFTBRIDGE_RUNTIME.bridgeProbeInFlight === probePromise) {
            SOFTBRIDGE_RUNTIME.bridgeProbeInFlight = null;
        }
    }
}

async function postSoftbridgePayloadToDesktop(endpoint, bridgeConfig, payload, options = {}) {
    const abortController = new AbortController();
    const timeoutHandle = window.setTimeout(() => {
        abortController.abort();
    }, bridgeConfig.timeoutMs);
    const logPrefix = options.logPrefix || "[Softbridge]";

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
        console.info(`${logPrefix} Bridge response received.`, {
            status: response.status,
            ok: response.ok,
            body: parsedBody,
        });

        if (!response.ok) {
            const message =
                parsedBody?.message ||
                parsedBody?.error ||
                `Softbridge request failed with status ${response.status}.`;
            throw new Error(`Bridge endpoint failed (${response.status}): ${message}`);
        }

        return parsedBody;
    } catch (error) {
        if (error?.name === "AbortError") {
            throw new Error(`Softbridge request timed out after ${bridgeConfig.timeoutMs} ms.`);
        }
        if (error instanceof TypeError) {
            throw new Error(
                `Desktop app not reachable at ${endpoint}: ${error.message || "Network request failed"}`
            );
        }
        throw error;
    } finally {
        window.clearTimeout(timeoutHandle);
    }
}

function quietlyProbeSoftbridgeDesktopBridgeFromPos(pos) {
    const bridgeConfig = buildSoftbridgeConfigFromPosConfig(pos?.config || {});
    if (!bridgeConfig.enabled) {
        return;
    }

    const now = Date.now();
    if (
        SOFTBRIDGE_RUNTIME.bridgeProbeInFlight ||
        now - SOFTBRIDGE_RUNTIME.lastBridgeProbeStartedAt < BRIDGE_PROBE_CACHE_MS
    ) {
        return;
    }

    probeSoftbridgeDesktopBridge(bridgeConfig).catch((error) => {
        console.info("[Softbridge] Desktop bridge is not reachable yet.", error);
    });
}

function safeParseSoftbridgeJson(rawBody) {
    try {
        return JSON.parse(rawBody);
    } catch {
        return { raw: rawBody };
    }
}

patch(ReceiptScreen.prototype, {
    setup() {
        super.setup(...arguments);
        this.notification = this.notification || useService("notification");
        this.sendToSoftbridge = useState({ status: "idle", error: null, acceptedAs: null });
        SOFTBRIDGE_RUNTIME.receiptScreen = this;
        ensureSoftbridgeWindowPrintPatch();

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

        try {
            await this._probeSoftbridgeBridge(bridgeConfig, { force: manual });
        } catch (error) {
            if (manual) {
                this._notifySoftbridge(error.message || "Softbridge bridge is not reachable.", "danger");
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
        const result = await this._sendSoftbridgeReceiptElementToBridge({
            bridgeConfig,
            receiptElement,
            source: "odoo_pos_receipt_screen",
            logPrefix: "[Softbridge][Receipt]",
            logContext: {
                orderUid: this._getSoftbridgeOrderKey(),
                orderName: this.currentOrder?.name || "",
                bridgeUrl: endpoint,
            },
        });
        const acceptedAs =
            result.responseBody?.accepted_as ||
            result.responseBody?.content_type ||
            result.payload.type;
        this.sendToSoftbridge.acceptedAs = acceptedAs;

        if (manual) {
            const message = acceptedAs === "receipt_image"
                ? "Receipt sent to Softbridge as a rendered receipt image."
                : `Receipt sent to Softbridge as ${acceptedAs}.`;
            this._notifySoftbridge(message, acceptedAs === "receipt_image" ? "success" : "warning");
        } else {
            console.info("Softbridge receipt queued.", result.responseBody);
        }

        return result.responseBody;
    },

    _getSoftbridgeConfig() {
        return buildSoftbridgeConfigFromPosConfig(this.pos.config || {});
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
        return buildSoftbridgePathEndpoint(bridgeConfig, BRIDGE_RECEIPT_PATH);
    },

    _buildSoftbridgeStatusEndpoint(bridgeConfig) {
        return buildSoftbridgePathEndpoint(bridgeConfig, BRIDGE_STATUS_PATH);
    },

    async _probeSoftbridgeBridge(bridgeConfig, options = {}) {
        return await probeSoftbridgeDesktopBridge(bridgeConfig, options);
    },

    async _sendSoftbridgeReceiptElementToBridge({
        bridgeConfig,
        receiptElement,
        source,
        payloadOptions = {},
        payloadAdjuster,
        logPrefix = "[Softbridge]",
        logContext = {},
    }) {
        const endpoint = this._buildSoftbridgeEndpoint(bridgeConfig);
        console.info(`${logPrefix} Building desktop bridge payload.`, {
            ...logContext,
            bridgeUrl: endpoint,
            payloadOptions,
        });
        const payload = await this._buildSoftbridgePayload(
            bridgeConfig,
            receiptElement,
            payloadOptions
        );
        if (source) {
            payload.source = source;
        }
        if (typeof payloadAdjuster === "function") {
            payloadAdjuster(payload);
        }

        const payloadKeys = Object.keys(payload || {});
        console.info(`${logPrefix} Payload prepared for desktop bridge.`, {
            ...logContext,
            bridgeUrl: endpoint,
            payloadKeys,
            payloadType: payload.type,
            captureMode: payload.capture_mode,
            hasStructuredReceipt: Boolean(payload.structured_receipt),
            hasRenderedImage: Boolean(payload.rendered_image || payload.image),
            htmlLength: String(payload.html || "").length,
            textLength: String(payload.text || "").length,
        });

        const responseBody = await this._postSoftbridgePayload(
            endpoint,
            bridgeConfig,
            payload,
            { logPrefix }
        );
        console.info(`${logPrefix} Desktop bridge accepted payload.`, {
            responseBody,
        });

        return {
            endpoint,
            payload,
            responseBody,
        };
    },

    async _buildSoftbridgePayload(bridgeConfig, receiptElement, options = {}) {
        const order = this.currentOrder;
        const receiptData = this._getSoftbridgeReceiptData();
        const resolvedReceiptElement = this._resolveSoftbridgeReceiptElement(receiptElement);
        const renderedReceiptCapture = await this._maybeCaptureSoftbridgeRenderedImage(
            resolvedReceiptElement,
            options.renderedImageTimeoutMs
        );
        const renderedImage = renderedReceiptCapture?.dataUrl || null;
        const renderedImageMeta = renderedReceiptCapture?.meta || null;
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
            payload.rendered_image_meta = renderedImageMeta;
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
        const lines = this._buildSoftbridgeStructuredLinesFromExport(receiptData);
        const summary = this._buildSoftbridgeStructuredSummaryFromExport(receiptData);
        const footerLines = this._buildSoftbridgeStructuredFooterLines(receiptData, header, company);
        const logoImage = await this._extractSoftbridgeReceiptAsset(receiptElement, "logo");
        const qrImage = await this._extractSoftbridgeReceiptAsset(receiptElement, "qr");
        const signatureLabel = this._extractSoftbridgeSignatureLabel(receiptElement);
        const domStructuredReceipt = this._extractSoftbridgeStructuredReceiptFromDom(receiptElement);
        const resolvedLines = this._selectBestSoftbridgeStructuredLines(
            lines,
            domStructuredReceipt?.lines || []
        );
        const resolvedSummary = this._hasMeaningfulSoftbridgeStructuredSummary(summary)
            ? summary
            : domStructuredReceipt?.summary || [];
        const resolvedFooterLines = this._mergeSoftbridgeStructuredTextLines(
            footerLines,
            domStructuredReceipt?.footerLines || []
        );
        const resolvedCompanyName =
            String(company.name || "").trim() ||
            domStructuredReceipt?.companyName ||
            "";
        const resolvedOrderName =
            String(receiptData?.name || "").trim() ||
            domStructuredReceipt?.orderName ||
            "";
        const resolvedDate =
            String(receiptData?.date || "").trim() ||
            domStructuredReceipt?.date ||
            "";
        const resolvedCashier =
            String(header.cashier || "").trim() ||
            domStructuredReceipt?.cashier ||
            "";
        const resolvedSignatureLabel =
            signatureLabel ||
            domStructuredReceipt?.signatureLabel ||
            "";

        return {
            company_name: resolvedCompanyName,
            order_name: resolvedOrderName,
            date: resolvedDate,
            cashier: resolvedCashier,
            logo_image: logoImage,
            qr_image: qrImage,
            signature_label: resolvedSignatureLabel,
            lines: resolvedLines,
            summary: resolvedSummary,
            footer_lines: resolvedFooterLines,
        };
    },

    _buildSoftbridgeStructuredLinesFromExport(receiptData) {
        const lines = [];
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
        return lines;
    },

    _buildSoftbridgeStructuredSummaryFromExport(receiptData) {
        const summary = [];
        const taxTotals = receiptData?.taxTotals || {};

        // Collect all tax groups
        const allTaxGroups = [];
        let subtotalAmount = "";
        for (const subtotal of taxTotals.subtotals || []) {
            subtotalAmount =
                subtotal.formatted_base_amount_currency ||
                subtotal.formatted_amount ||
                this._formatSoftbridgeMoney(subtotal.base_amount_currency);
            for (const taxGroup of subtotal.tax_groups || []) {
                allTaxGroups.push({
                    label: String(taxGroup.group_name || "Tax").trim(),
                    amount:
                        taxGroup.formatted_tax_amount ||
                        this._formatSoftbridgeMoney(taxGroup.tax_amount_currency),
                });
            }
        }

        // Subtotal with GCT on same line
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

        const totalAmount =
            taxTotals.formatted_order_total ||
            this._formatSoftbridgeMoney(
                taxTotals.order_sign
                    ? taxTotals.order_sign * taxTotals.order_total
                    : taxTotals.order_total
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

        // Payment method only - no amount
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
                amount: this._formatSoftbridgeMoney(receiptData.total_discount),
                emphasis: false,
            });
        }

        return summary;
    },

    _buildSoftbridgeStructuredFooterLines(receiptData, header, company) {
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

        return this._mergeSoftbridgeStructuredTextLines(
            [],
            [
                header.header,
                receiptData?.footer,
                company.name,
                companyAddressLine,
                companyContactLine,
            ].flatMap((value) => String(value || "").split(/\r?\n/))
        );
    },

    _hasMeaningfulSoftbridgeStructuredLines(lines) {
        return this._countMeaningfulSoftbridgeStructuredLines(lines) > 0;
    },

    _countMeaningfulSoftbridgeStructuredLines(lines) {
        if (!Array.isArray(lines)) {
            return 0;
        }
        return lines.filter((line) => {
            const name = String(line?.name || "").trim();
            const amount = String(line?.amount || "").trim();
            const qty = String(line?.qty || "").trim();
            return name || amount || qty;
        }).length;
    },

    _selectBestSoftbridgeStructuredLines(exportLines = [], domLines = []) {
        const exportCount = this._countMeaningfulSoftbridgeStructuredLines(exportLines);
        const domCount = this._countMeaningfulSoftbridgeStructuredLines(domLines);
        if (!exportCount) {
            return domLines || [];
        }
        if (domCount > exportCount) {
            return domLines;
        }
        return exportLines;
    },

    _hasMeaningfulSoftbridgeStructuredSummary(summary) {
        return Array.isArray(summary) && summary.some((row) => {
            const label = String(row?.label || "").trim();
            return label;
        });
    },

    _mergeSoftbridgeStructuredTextLines(primaryLines = [], fallbackLines = []) {
        const mergedLines = [];
        [...primaryLines, ...fallbackLines]
            .flatMap((value) => String(value || "").split(/\r?\n/))
            .map((value) => this._normalizeSoftbridgeReceiptText(value))
            .filter(Boolean)
            .forEach((value) => {
                if (!mergedLines.some((existing) => existing.toLowerCase() === value.toLowerCase())) {
                    mergedLines.push(value);
                }
            });
        return mergedLines;
    },

    _extractSoftbridgeStructuredReceiptFromDom(receiptElement) {
        const textLines = this._extractSoftbridgeReceiptTextLines(receiptElement);
        if (!textLines.length) {
            return null;
        }

        const signatureIndex = textLines.findIndex((line) => /customer signature/i.test(line));
        const visibleLines = signatureIndex >= 0 ? textLines.slice(0, signatureIndex) : [...textLines];
        if (!visibleLines.length) {
            return null;
        }

        const orderNameIndex = visibleLines.findIndex((line) => this._looksLikeSoftbridgeOrderNameLine(line));
        const dateIndex = visibleLines.findIndex((line) => this._looksLikeSoftbridgeDateLine(line));
        const cashierIndex = visibleLines.findIndex((line) => this._looksLikeSoftbridgeCashierLine(line));
        const headerEndIndex = Math.max(orderNameIndex, dateIndex, cashierIndex);
        const companySearchLimit = headerEndIndex >= 0 ? headerEndIndex : Math.min(visibleLines.length, 4);
        const companyName =
            visibleLines.find((line, index) =>
                index < companySearchLimit &&
                !this._looksLikeSoftbridgeOrderNameLine(line) &&
                !this._looksLikeSoftbridgeDateLine(line) &&
                !this._looksLikeSoftbridgeCashierLine(line) &&
                !this._parseSoftbridgeDomStructuredLine(line) &&
                !this._parseSoftbridgeDomSummaryRow(line)
            ) || "";
        const bodyLines = visibleLines
            .slice(headerEndIndex >= 0 ? headerEndIndex + 1 : 0)
            .filter(Boolean);

        let summaryStartIndex = -1;
        for (let index = 0; index < bodyLines.length; index += 1) {
            if (this._looksLikeSoftbridgeSummaryLabel(bodyLines[index])) {
                summaryStartIndex = index;
                break;
            }
        }

        let summaryEndIndex = bodyLines.length;
        if (summaryStartIndex >= 0) {
            for (let index = summaryStartIndex; index < bodyLines.length; index += 1) {
                const line = bodyLines[index];
                if (
                    index > summaryStartIndex &&
                    this._looksLikeSoftbridgeFooterLine(line)
                ) {
                    summaryEndIndex = index;
                    break;
                }
            }
        }

        const itemTextLines = bodyLines.slice(0, summaryStartIndex >= 0 ? summaryStartIndex : bodyLines.length);
        const summaryTextLines =
            summaryStartIndex >= 0 ? bodyLines.slice(summaryStartIndex, summaryEndIndex) : [];
        const footerLines =
            summaryStartIndex >= 0 ? bodyLines.slice(summaryEndIndex).filter(Boolean) : [];

        return {
            companyName,
            orderName: orderNameIndex >= 0 ? visibleLines[orderNameIndex] : "",
            date: dateIndex >= 0 ? visibleLines[dateIndex] : "",
            cashier: cashierIndex >= 0 ? visibleLines[cashierIndex] : "",
            signatureLabel: signatureIndex >= 0 ? "Customer Signature" : "",
            lines: this._buildSoftbridgeDomStructuredLines(itemTextLines),
            summary: this._buildSoftbridgeDomStructuredSummary(summaryTextLines),
            footerLines,
        };
    },

    _extractSoftbridgeReceiptTextLines(receiptElement) {
        const rawText = receiptElement?.innerText || receiptElement?.textContent || "";
        return String(rawText || "")
            .replace(/\r\n/g, "\n")
            .replace(/\r/g, "\n")
            .split("\n")
            .map((line) => this._normalizeSoftbridgeReceiptText(line).replace(/\s+/g, " ").trim())
            .filter(Boolean);
    },

    _buildSoftbridgeDomStructuredLines(textLines) {
        const structuredLines = [];
        for (let index = 0; index < textLines.length; index += 1) {
            const textLine = textLines[index];
            const parsedLine = this._parseSoftbridgeDomStructuredLine(textLine);
            if (parsedLine) {
                structuredLines.push(parsedLine);
                continue;
            }

            if (!this._looksLikeSoftbridgeQtyLine(textLine)) {
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
                    this._looksLikeSoftbridgeQtyLine(candidate) ||
                    this._looksLikeSoftbridgeSummaryLabel(candidate) ||
                    this._looksLikeSoftbridgeFooterLine(candidate)
                ) {
                    break;
                }

                index += 1;
                if (!name && !this._looksLikeSoftbridgeAmountOnlyLine(candidate)) {
                    name = candidate;
                    continue;
                }

                if (!amount && this._looksLikeSoftbridgeAmountOnlyLine(candidate)) {
                    amount = this._extractSoftbridgeTrailingAmountSafe(candidate);
                    continue;
                }

                if (/^note\b[:\-]?\s*/i.test(candidate)) {
                    const noteText = candidate.replace(/^note\b[:\-]?\s*/i, "").trim();
                    note = note
                        ? `${note} | ${noteText}`
                        : noteText;
                    continue;
                }

                subline = subline
                    ? `${subline} | ${candidate}`
                    : candidate;
            }

            if (name || amount) {
                structuredLines.push({
                    qty: quantity,
                    name,
                    amount,
                    subline,
                    note,
                });
                continue;
            }
        }

        return structuredLines.filter((line) => {
            const name = String(line?.name || "").trim();
            const amount = String(line?.amount || "").trim();
            const qty = String(line?.qty || "").trim();
            return name || amount || qty;
        });
    },

    _parseSoftbridgeDomStructuredLine(textLine) {
        const normalizedLine = this._normalizeSoftbridgeReceiptText(textLine).replace(/\s+/g, " ").trim();
        if (!normalizedLine) {
            return null;
        }
        if (
            this._looksLikeSoftbridgeOrderNameLine(normalizedLine) ||
            this._looksLikeSoftbridgeDateLine(normalizedLine) ||
            this._looksLikeSoftbridgeCashierLine(normalizedLine) ||
            this._parseSoftbridgeDomSummaryRow(normalizedLine)
        ) {
            return null;
        }

        const quantityMatch = normalizedLine.match(/^(\d+(?:\.\d+)?)\s+(.+)$/);
        if (!quantityMatch) {
            return null;
        }

        const trailingAmount = this._extractSoftbridgeTrailingAmountSafe(normalizedLine);
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
    },

    _buildSoftbridgeDomStructuredSummary(textLines) {
        const summaryRows = [];
        for (let index = 0; index < textLines.length; index += 1) {
            const textLine = textLines[index];
            const parsedRow = this._parseSoftbridgeDomSummaryRow(textLine);
            if (parsedRow) {
                summaryRows.push(parsedRow);
                continue;
            }

            if (!this._looksLikeSoftbridgeSummaryLabel(textLine)) {
                continue;
            }

            const nextLine = textLines[index + 1] || "";
            const amount = this._looksLikeSoftbridgeAmountOnlyLine(nextLine)
                ? this._extractSoftbridgeTrailingAmountSafe(nextLine)
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
    },

    _parseSoftbridgeDomSummaryRow(textLine) {
        const normalizedLine = this._normalizeSoftbridgeReceiptText(textLine).replace(/\s+/g, " ").trim();
        if (!normalizedLine) {
            return null;
        }

        if (!this._looksLikeSoftbridgeSummaryLabel(normalizedLine)) {
            return null;
        }

        const trailingAmount = this._extractSoftbridgeTrailingAmountSafe(normalizedLine);
        if (!trailingAmount) {
            return null;
        }
        const label = trailingAmount
            ? normalizedLine.slice(0, normalizedLine.length - trailingAmount.length).trim()
            : normalizedLine;
        return {
            label,
            amount: trailingAmount,
            emphasis: /^total\b/i.test(label),
        };
    },

    _extractSoftbridgeTrailingAmount(textLine) {
        const normalizedLine = this._normalizeSoftbridgeReceiptText(textLine).replace(/\s+/g, " ").trim();
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
    },

    _extractSoftbridgeTrailingAmountSafe(textLine) {
        const normalizedLine = this._normalizeSoftbridgeReceiptText(textLine).replace(/\s+/g, " ").trim();
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
    },

    _looksLikeSoftbridgeQtyLine(textLine) {
        return /^\d+(?:\.\d+)?$/.test(String(textLine || "").trim());
    },

    _looksLikeSoftbridgeAmountOnlyLine(textLine) {
        const normalizedLine = this._normalizeSoftbridgeReceiptText(textLine).replace(/\s+/g, " ").trim();
        if (!normalizedLine) {
            return false;
        }
        const amount = this._extractSoftbridgeTrailingAmountSafe(normalizedLine);
        return Boolean(amount) && normalizedLine === amount;
    },

    _looksLikeSoftbridgeSummaryLabel(textLine) {
        const normalizedLine = this._normalizeSoftbridgeReceiptText(textLine).replace(/\s+/g, " ").trim();
        if (!normalizedLine) {
            return false;
        }
        return /^(subtotal|total|tax\b|vat\b|gst\b|change\b|rounding\b|discounts?\b|cash\b|card\b|visa\b|master\s*card\b|mastercard\b|amex\b|debit\b|credit\b|payment\b|tender\b|balance\b|tip\b|service\b|ja\s+tax\b)/i.test(normalizedLine);
    },

    _looksLikeSoftbridgeFooterLine(textLine) {
        const normalizedLine = this._normalizeSoftbridgeReceiptText(textLine).replace(/\s+/g, " ").trim();
        if (!normalizedLine) {
            return false;
        }
        if (/^(bus|table|guest|gct|tel|phone|powered by|email|website)\b/i.test(normalizedLine)) {
            return true;
        }
        return normalizedLine.includes(",") || /\(\d{3}\)/.test(normalizedLine);
    },

    _looksLikeSoftbridgeOrderNameLine(textLine) {
        return /\b(invoice|ticket|receipt|order)\b/i.test(textLine);
    },

    _looksLikeSoftbridgeDateLine(textLine) {
        const normalizedLine = this._normalizeSoftbridgeReceiptText(textLine);
        return /(\b\d{1,2}[\/-]\d{1,2}[\/-]\d{2,4}\b|\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b)/i.test(normalizedLine) &&
            /(\b\d{1,2}:\d{2}\b|\bam\b|\bpm\b|\b\d{4}\b)/i.test(normalizedLine);
    },

    _looksLikeSoftbridgeCashierLine(textLine) {
        return /\b(served by|cashier|employee|server)\b/i.test(textLine);
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

        try {
            return await this._convertSoftbridgeNodeToDataUrl(assetNode);
        } catch (error) {
            console.warn(`Softbridge ${kind} asset could not be converted for receipt payload.`, error);
            return null;
        }
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

    async _maybeCaptureSoftbridgeRenderedImage(receiptElement, timeoutMs = 0) {
        if (!receiptElement) {
            return null;
        }
        try {
            const capturePromise = this._captureSoftbridgeReceiptImage(receiptElement).catch(
                (error) => {
                    console.warn("Softbridge rendered receipt capture failed.", error);
                    return null;
                }
            );
            if (!timeoutMs) {
                return await capturePromise;
            }
            let timeoutHandle = null;
            try {
                return await Promise.race([
                    capturePromise,
                    new Promise((resolve) => {
                        timeoutHandle = window.setTimeout(() => {
                            console.warn(
                                `Softbridge rendered receipt capture exceeded ${timeoutMs} ms; sending structured/text payload without the rendered image.`
                            );
                            resolve(null);
                        }, timeoutMs);
                    }),
                ]);
            } finally {
                if (timeoutHandle) {
                    window.clearTimeout(timeoutHandle);
                }
            }
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
        return {
            dataUrl: canvas.toDataURL("image/png"),
            meta: this._buildSoftbridgeRenderedImageMeta(targetElement, {
                cssWidthPx: width,
                cssHeightPx: height,
                canvasWidthPx: canvas.width,
                canvasHeightPx: canvas.height,
                captureScale: scale,
            }),
        };
    },

    _buildSoftbridgeRenderedImageMeta(targetElement, captureInfo) {
        const bounds = targetElement?.getBoundingClientRect?.() || {};
        const captureTarget = this._describeSoftbridgeReceiptElement(targetElement);
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
    },

    _describeSoftbridgeReceiptElement(targetElement) {
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
    },

    _resolveSoftbridgeReceiptElement(receiptElement) {
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
        const activeOrder = resolveSoftbridgeCurrentOrder(this) || this.currentOrder;
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

    async _postSoftbridgePayload(endpoint, bridgeConfig, payload, options = {}) {
        const abortController = new AbortController();
        const timeoutHandle = window.setTimeout(() => {
            abortController.abort();
        }, bridgeConfig.timeoutMs);
        const logPrefix = options.logPrefix || "";

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
            if (logPrefix) {
                console.info(`${logPrefix} Desktop bridge response.`, {
                    status: response.status,
                    ok: response.ok,
                    body: parsedBody,
                });
            }
            if (!response.ok) {
                const message =
                    parsedBody?.message ||
                    parsedBody?.error ||
                    `Softbridge request failed with status ${response.status}.`;
                throw new Error(`Bridge endpoint failed (${response.status}): ${message}`);
            }

            return parsedBody;
        } catch (error) {
            if (error?.name === "AbortError") {
                throw new Error(
                    `Softbridge request timed out after ${bridgeConfig.timeoutMs} ms.`
                );
            }
            if (error instanceof TypeError) {
                throw new Error(
                    `Desktop app not reachable at ${endpoint}: ${error.message || "Network request failed"}`
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
        return safeParseSoftbridgeJson(rawBody);
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
