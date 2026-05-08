# Softbridge Desktop Installer

This folder is only for building and verifying the Windows Desktop App installer. It is intentionally separate from the Odoo modules.

## Build The Installer

Open PowerShell from the repository root or from this folder and run:

```powershell
.\WindowsExecutable\softbridge_desktop_installer\build-installer.ps1 -Clean
```

The script calls the Windows desktop build in `WindowsExecutable/build-windows-app.ps1`, packages the app with `jpackage`, then copies the generated installer to:

```text
WindowsExecutable/softbridge_desktop_installer/output/
```

## Install

1. Run the generated `ReceiptBridgeDesktop-<version>.exe`.
2. Launch `ReceiptBridgeDesktop` from the Start menu.
3. Add or select the cashier machine's thermal printer.
4. Confirm the bridge shows as running.

## Verify The Bridge

The desktop app listens locally on port `9900`.

Run:

```powershell
Invoke-RestMethod http://127.0.0.1:9900/integration/status
```

Expected result includes:

- `status: running`
- `bridge: windows-desktop`
- `default_printer_configured: true` after a printer is selected
- `odoo_receipt: /odoo/receipt` in the endpoints list

## Windows Firewall

The Odoo POS browser talks to the bridge on the same cashier Windows machine through:

```text
http://127.0.0.1:9900/odoo/receipt
```

Loopback traffic normally does not need a firewall rule. If the bridge is configured to accept traffic from another device, allow inbound TCP `9900` for `ReceiptBridgeDesktop.exe` on the private network only.

## Runtime Checks

Use these checks before testing from Odoo:

1. The desktop app window is open and says the local bridge is running.
2. `GET /integration/status` returns `running`.
3. At least one printer profile exists and is default/active.
4. The Queue screen records a new job when Odoo sends a print.
5. The recent event log shows print received, printer selected, QR/barcode detection, and success or failure.

## VPS Note

The Odoo module should stay on the VPS. The desktop app installer belongs on each cashier Windows machine because it communicates with the local thermal printer through `127.0.0.1:9900`.
