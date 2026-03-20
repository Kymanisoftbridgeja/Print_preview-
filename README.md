# ReceiptBridge - Android ESC/POS Print Service

ReceiptBridge is an Android application designed to bypass the standard system print preview and print directly to ESC/POS printers via Bluetooth or Network. It acts as a bridge for web applications (like Odoo, ERPNext) to print commands to local hardware.

## Features

- **Direct Printing**: Uses raw ESC/POS commands.
- **Protocols**: Bluetooth (SPP) and Network (TCP/IP 9100).
- **Background Service**: Listens on `http://127.0.0.1:9900/print`.
- **Deep Linking**: Trigger prints via `receiptbridge://print?payload=BASE64_JSON`.
- **Customizable**: Adjustable width (58mm/80mm), feed lines, cut options.

## Setup

1. **Clone & Open**: Open the project in Android Studio.
2. **Build**: Build and Run on an Android Device.
3. **Permissions**: Grant Bluetooth and Notification permissions when prompted.

## Usage

### 1. Configure Printer
- Open the App.
- Go to "Manage Printers".
- Click "+" to add a printer profile.
- Select Connection Type (Network or Bluetooth) and enter address (IP or MAC).
- Set as Default if desired.

### 2. Print via HTTP
Send a POST request to `http://127.0.0.1:9900/print` with the JSON payload.
See [ODOO_INTEGRATION.md](ODOO_INTEGRATION.md) for payload details.

### 3. Print via Deep Link
Construct a URL: `receiptbridge://print?payload={BASE64_ENCODED_JSON}`
Open this URL from any browser or app on the device.

## API Specification

**Endpoint**: `POST /print`
**Port**: `9900`

**Request Body**:
```json
{
  "printer_profile_id": "optional-uuid",
  "paper_size": "80mm",
  "content": {
    "type": "escpos_blocks",
    "blocks": [
       {"cmd": "text", "value": "Hello World"},
       {"cmd": "feed", "value": 2},
       {"cmd": "cut"}
    ]
  }
}
```
