# Softbridge POS Bridge

This Odoo addon sends the POS receipt screen to the Softbridge Windows desktop bridge.

## What it adds

- POS settings for the Windows bridge URL, optional token, payload mode, timeout, and auto-send behavior
- Automatic receipt delivery when the Odoo receipt screen opens
- A manual `Send to Softbridge` button on the receipt screen

## Target bridge endpoint

The addon sends `POST` requests to:

- `http://127.0.0.1:9900/odoo/receipt`

If you change the base URL in Odoo settings, the addon still appends `/odoo/receipt`.

## Install

1. Copy `softbridge_pos_bridge` into your Odoo addons path.
2. Update the Apps list.
3. Install `Softbridge POS Bridge`.
4. Open `Point of Sale -> Configuration -> Settings`.
5. Enable `Softbridge Windows Bridge`.
6. Set the Windows bridge URL and token if needed.
7. Use `Rendered Receipt Image` when you want the printed ticket to match the on-screen receipt as closely as possible.
8. Switch back to `Receipt Text` if you need the simplest fallback output.

## Notes

- This addon targets the modern Odoo POS frontend used by Odoo 17 and 18.
- The Windows Softbridge desktop app must already be running on the same machine as the browser session.
