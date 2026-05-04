# Odoo Receipt Protocol Report

Date: 2026-05-04

## Goal

Make the Windows desktop bridge print Odoo receipts through the same direct receipt-command path philosophy as the mobile app, instead of relying on the browser-rendered receipt snapshot as the printed output.

## Repo Findings

1. The Android app is built around direct ESC/POS printing.
   - Local source reviewed:
     - `App/app/src/main/java/com/receiptbridge/escpos/PrintPayload.kt`
     - `App/app/src/main/java/com/receiptbridge/escpos/PrinterDriver.kt`
     - `App/app/src/main/java/com/receiptbridge/escpos/EscPosBuilder.kt`
   - The Android driver accepts `content.type = "escpos_blocks"` and prints directly to ESC/POS transports.
   - For many jobs it rasterizes the receipt layout itself before sending ESC/POS image commands, which gives more consistent output.

2. The Odoo addon already sends structured receipt data in addition to the rendered image.
   - Local source reviewed:
     - `OdooModule/softbridge_pos_bridge/static/src/pos/receipt_bridge.js`
   - The addon builds a `structured_receipt` object from `orderExportForPrinting(...)`.
   - It also captures a rendered browser image and includes it in the payload when capture succeeds.

3. The Windows bridge already recognizes `odoo_structured`.
   - Local source reviewed:
     - `WindowsExecutable/ReceiptBridgeDesktop/src/main/kotlin/com/receiptbridge/desktop/service/DesktopBridgeIntegration.kt`
     - `WindowsExecutable/ReceiptBridgeDesktop/src/main/kotlin/com/receiptbridge/desktop/service/DesktopServices.kt`
   - Before this change, the Windows structured path still rendered the receipt with simpler text/block logic than the Android path.

## Protocol Recommendation

Use direct ESC/POS printing over the Windows bridge, with Odoo receipt data normalized into a structured intermediate format and then rendered by the Windows app into receipt-ready ESC/POS output.

In practical terms for this codebase:

1. Odoo should keep posting to `POST /odoo/receipt`.
2. The Windows app should prefer `structured_receipt` over the browser snapshot for actual print composition.
3. The Windows driver should render the structured receipt into receipt-width raster bands and send those bands as ESC/POS image commands to the printer.

This is the closest Windows-side match to how the mobile app behaves:

- mobile path: structured job -> controlled receipt layout -> ESC/POS output
- updated Windows path: structured job -> controlled receipt layout -> ESC/POS output

## External Research

Official Odoo documentation confirms that:

1. Odoo directly supports Epson ePOS printers over Epson's JavaScript ePOS protocol when the printer itself supports it.
2. ESC/POS printers are otherwise used through a bridge/IoT-style layer.

Sources:

- Odoo 18 ePOS printers:
  https://www.odoo.com/documentation/18.0/applications/sales/point_of_sale/configuration/epos_printers.html
- Odoo 19 receipt printers:
  https://www.odoo.com/documentation/19.0/applications/sales/point_of_sale/hardware_network/receipt_printers.html

Official Epson documentation confirms that:

1. ESC/POS is the low-level command system used by TM printers.
2. Epson ePOS JavaScript is a higher-level browser/web protocol distinct from raw ESC/POS.

Sources:

- Epson ESC/POS command reference:
  https://download4.epson.biz/sec_pubs/pos/reference_en/escpos/
- Epson ePOS SDK for JavaScript:
  https://download4.epson.biz/sec_pubs/pos/reference_en/technology/epson_epos_sdk.html

## Why This Protocol Fits This Project

The current Windows desktop app already prints to network printers over raw TCP port `9100`, which is the correct place for ESC/POS command output in this repo. Moving the Windows Odoo path toward structured receipt rendering is lower risk than introducing Epson ePOS JavaScript or printer-vendor-specific XML into the desktop bridge.

That keeps the architecture aligned across platforms:

- same local bridge model
- same saved printer profiles
- same ESC/POS output target
- no dependence on browser print preview

## Windows-Only Fix Applied

Files changed:

- `WindowsExecutable/ReceiptBridgeDesktop/src/main/kotlin/com/receiptbridge/desktop/service/DesktopServices.kt`

What changed:

1. The Windows Odoo structured receipt path now attempts a controlled receipt raster render from structured data before falling back to simple block-by-block printing.
2. Structured receipt header/footer interpretation was improved so company details and notices are placed more cleanly in the generated receipt layout.
3. The rendered output is converted into receipt-width ESC/POS raster bands before sending to the printer, which better matches the Android/mobile print strategy.
4. The Windows bridge now prefers `structured_receipt` whenever it is present in the incoming payload, even if the declared content type is `receipt_image`.

## Important Limitation

Because the request was to avoid touching Android-related files and to keep edits limited to the Windows application, the Odoo addon was not changed.

That means:

- the Odoo browser code may still capture a rendered image upstream
- but the updated Windows app now prefers structured receipt data for the printed result

If the goal later becomes to stop creating the browser snapshot entirely, that would require a separate change in:

- `OdooModule/softbridge_pos_bridge/static/src/pos/receipt_bridge.js`

That change was intentionally not made in this pass.
