# Windows Executable Folder

This folder contains the Windows desktop port and its packaged output so it stays separate from the Android app in `App/`.

## Structure

- `ReceiptBridgeDesktop/` contains the Kotlin Compose for Desktop Windows app project.
- `build-windows-app.ps1` builds the Windows app image and copies it into `dist/`.
- `dist/` holds the packaged Windows output, including the launcher `.exe` at the root of the app image folder.

## Notes

- The Windows app mirrors the Android app's main flows: Home, Printers, Queue, and Settings.
- The Windows app supports network ESC/POS printing and the local HTTP queue bridge.
- The Windows app supports Windows-installed USB printer queues and can create a Windows queue automatically for detected USB receipt printer ports such as `USB001`.
- Bluetooth profiles are preserved in the UI for parity, but actual Windows-side Bluetooth printing is not implemented yet.
