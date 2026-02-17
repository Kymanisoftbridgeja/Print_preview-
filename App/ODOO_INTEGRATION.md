# Odoo Integration Guide

Since Odoo SaaS cannot install custom local drivers, ReceiptBridge offers two ways to communicate with the Android device's hardware.

## Option A: Local Localhost (Recommended for specialized Android POS)
If running Odoo in a browser (Chrome/Kiosk) on the *same* Android device as ReceiptBridge:

1. **Ensure ReceiptBridge is running**: The "ReceiptBridge Server Running" notification should be visible.
2. **Javascript / Python Call**:
   Make a request to `http://127.0.0.1:9900/print`. Note that mixed-content (HTTPS to HTTP) might be blocked by some browsers unless configured or using a specific browser.

   **Example (Javascript)**:
   ```javascript
   const payload = {
      content: {
         type: "escpos_blocks",
         blocks: [
            { cmd: "align", value: "center" },
            { cmd: "text", value: "My Store" },
            { cmd: "cut" }
         ]
      }
   };
   
   fetch("http://127.0.0.1:9900/print", {
      method: "POST",
      body: JSON.stringify(payload)
   }).then(r => console.log("Printed"));
   ```

## Option B: Deep Linking (Works everywhere)
Generate a link that the user clicks, or redirect the user to this link.

**Format**: `receiptbridge://print?payload=<BASE64_JSON>`

**Python Example (Odoo Controller)**:
```python
import json
import base64
from odoo import http

class ReceiptController(http.Controller):
    @http.route('/print_receipt/<int:order_id>', auth='user')
    def print_receipt(self, order_id):
        # 1. Generate Receipt Data
        order = request.env['pos.order'].browse(order_id)
        receipt_data = {
            "content": {
                "type": "escpos_blocks",
                "blocks": [
                    {"cmd": "align", "value": "center"},
                    {"cmd": "text", "value": order.company_id.name},
                    {"cmd": "text", "value": "Order %s" % order.name},
                    {"cmd": "feed", "value": 3},
                    {"cmd": "cut"}
                ]
            }
        }
        
        # 2. Encode
        json_str = json.dumps(receipt_data)
        b64_str = base64.b64encode(json_str.encode('utf-8')).decode('utf-8')
        
        # 3. Redirect to App
        url = f"receiptbridge://print?payload={b64_str}"
        return request.redirect(url)
```

## JSON Payload Reference

### Blocks
- **text**: `{ "cmd": "text", "value": "String" }`
- **align**: `{ "cmd": "align", "value": "left|center|right" }`
- **feed**: `{ "cmd": "feed", "value": n_lines }`
- **cut**: `{ "cmd": "cut", "value": "full|partial" }`
- **row2**: `{ "cmd": "row2", "left": "Item", "right": "Price" }`
