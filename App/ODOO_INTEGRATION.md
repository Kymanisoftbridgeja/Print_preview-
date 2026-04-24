# Odoo Integration Guide

ReceiptBridge is intended for Odoo or browser-based POS flows running on the same Android device as the printer bridge.

## Transport options

### Option A: Localhost HTTP

Use this when Odoo runs in a browser on the same Android device as ReceiptBridge.

1. Make sure ReceiptBridge is running and the foreground notification is visible.
2. Send a `POST` request to `http://127.0.0.1:9900/print`.
3. Include `printer_profile_id` if the job should bypass the current default printer.

```javascript
const payload = {
  printer_profile_id: "optional-printer-id",
  copies: 1,
  content: {
    type: "escpos_blocks",
    blocks: [
      { cmd: "align", value: "center" },
      { cmd: "text", value: "My Store" },
      { cmd: "qr", value: "https://example.com/order/123" },
      { cmd: "cut", value: "full" }
    ]
  }
};

fetch("http://127.0.0.1:9900/print", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify(payload)
}).then(async (response) => {
  const body = await response.json();
  console.log(body);
});
```

Note: HTTPS pages may block HTTP localhost requests depending on the browser and kiosk environment.

### Option B: Deep link

Use this when the browser cannot call the local server directly.

- Format: `receiptbridge://print?payload=<BASE64_JSON>`
- ReceiptBridge decodes the JSON and queues the job locally.

```python
import base64
import json
from odoo import http
from odoo.http import request

class ReceiptController(http.Controller):
    @http.route('/print_receipt/<int:order_id>', auth='user')
    def print_receipt(self, order_id):
        order = request.env['pos.order'].browse(order_id)
        receipt_data = {
            "content": {
                "type": "escpos_blocks",
                "blocks": [
                    {"cmd": "align", "value": "center"},
                    {"cmd": "text", "value": order.company_id.name},
                    {"cmd": "text", "value": f"Order {order.name}"},
                    {"cmd": "cut", "value": "full"}
                ]
            }
        }

        payload = base64.b64encode(
            json.dumps(receipt_data).encode("utf-8")
        ).decode("utf-8")

        return request.redirect(f"receiptbridge://print?payload={payload}")
```

## Payload reference

### Top-level fields

- `printer_profile_id`: optional profile id to route the job to a specific printer.
- `paper_size`: optional, informational.
- `copies`: optional integer, defaults to `1`.
- `content.type`: currently `escpos_blocks`.
- `content.blocks`: ordered ESC/POS block list.

### Supported blocks

- `text`: `{ "cmd": "text", "value": "String" }`
- `align`: `{ "cmd": "align", "value": "left|center|right" }`
- `feed`: `{ "cmd": "feed", "value": 2 }`
- `cut`: `{ "cmd": "cut", "value": "full|partial" }`
- `row2`: `{ "cmd": "row2", "left": "Item", "right": "$5.00" }`
- `qr`: `{ "cmd": "qr", "value": "https://example.com", "left": "4" }`
- `image`: `{ "cmd": "image", "value": "<base64 image>", "left": "384", "right": "120" }`
- `drawer`: `{ "cmd": "drawer" }`
- `beep`: `{ "cmd": "beep" }`
- `charset`: `{ "cmd": "charset", "value": "UTF-8" }`
- `codepage`: `{ "cmd": "codepage", "value": 0 }`
- `raw`: `{ "cmd": "raw", "value": "1B40" }`

### Example full payload

```json
{
  "printer_profile_id": "optional-printer-id",
  "copies": 2,
  "content": {
    "type": "escpos_blocks",
    "blocks": [
      { "cmd": "align", "value": "center" },
      { "cmd": "text", "value": "Coffee Shop" },
      { "cmd": "row2", "left": "Latte", "right": "$4.50" },
      { "cmd": "row2", "left": "Muffin", "right": "$2.25" },
      { "cmd": "qr", "value": "https://example.com/order/123" },
      { "cmd": "feed", "value": 2 },
      { "cmd": "cut", "value": "full" }
    ]
  }
}
```
