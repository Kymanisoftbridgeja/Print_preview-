# ReceiptBridge - Android ESC/POS Print Service

ReceiptBridge is an Android application that bypasses the system print preview and sends ESC/POS jobs directly to receipt printers. It is intended for Android-based POS workflows where a browser or local web app needs to print to nearby hardware.

## Features

- Direct ESC/POS printing with no Android print dialog.
- Printer transports: USB, Bluetooth SPP, and network printers on TCP port `9100`.
- Local HTTP bridge on `http://127.0.0.1:9900/print`.
- Deep link printing via `receiptbridge://print?payload=BASE64_JSON`.
- Printer discovery for Bluetooth, USB, and local-network printers.
- Default printer management plus per-job `printer_profile_id` targeting.
- ESC/POS blocks for text, alignment, rows, feed, cut, QR codes, images, raw bytes, drawer open, beep, charset, and codepage.
- Global header/footer settings, including base64 header logos.
- Active/history queue with retry, re-print, history cleanup, and retention settings.

## Setup

1. Open the project in Android Studio.
2. Build and install on an Android device with USB host support if you plan to use USB printers.
3. Grant Bluetooth and notification permissions when prompted.
4. Connect at least one printer and mark a default printer, or pass `printer_profile_id` in each job.

## Usage

### 1. Configure printers

- Open the app and go to `Manage Printers`.
- Add a printer profile for `Network`, `Bluetooth`, or `USB`.
- Use the built-in discovery actions where available.
- Set a default printer from the profile list, or target a specific printer per job with `printer_profile_id`.

### 2. Print via HTTP

Send a `POST` request to `http://127.0.0.1:9900/print` with a JSON payload. Invalid payloads return `400`.

### 3. Print via deep link

Construct a URL like `receiptbridge://print?payload={BASE64_ENCODED_JSON}` and open it on the device.

## API

- Endpoint: `POST /print`
- Port: `9900`
- Status endpoint: `GET /status`

### Example payload

```json
{
  "printer_profile_id": "optional-uuid",
  "paper_size": "80mm",
  "copies": 2,
  "content": {
    "type": "escpos_blocks",
    "blocks": [
      { "cmd": "align", "value": "center" },
      { "cmd": "text", "value": "ReceiptBridge Demo" },
      { "cmd": "qr", "value": "https://example.com/order/123" },
      { "cmd": "feed", "value": 2 },
      { "cmd": "cut", "value": "full" }
    ]
  }
}
```

### Notes

- If `printer_profile_id` is omitted, ReceiptBridge uses the current default printer.
- `copies` defaults to `1`.
- `image` blocks accept base64 image data and are rasterized for ESC/POS automatically.
- Global settings can prepend a shared header logo or text and append a footer to every job.

See [ODOO_INTEGRATION.md](ODOO_INTEGRATION.md) for payload examples and block reference details.
