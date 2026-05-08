# Softbridge POS Browser Print

This Odoo addon prints the POS receipt directly from the browser instead of sending it to the Windows bridge.

## What it adds

- POS settings to enable browser receipt printing
- An optional `Print in Browser` button on the POS receipt screen
- Optional auto-print when the receipt screen opens
- A receipt-only print frame so the browser prints the actual Odoo receipt content instead of the whole screen

## Notes

- This uses the browser print flow, so the browser or operating system still chooses the printer.
- Standard web pages cannot silently send raw ESC/POS bytes to an IP printer.
- This addon is designed to be installed separately from `softbridge_pos_bridge` and does not modify that module.
