# Field Service Mobile Reports

This workspace contains two deliverables:

- `odoo_addons/field_service_road_reports`: Odoo Service Reports module that adds the backend report model, menus, views, review workflow, quotations, invoices, and Field Service job buttons.
- `odoo_addons/field_service_mobile_connector`: Odoo connector addon for Android sync into the existing `field_service_road_reports` workflow.
- `android/TechnicianServiceReports`: native Android Kotlin app using Room for offline storage and WorkManager for retryable sync.

## Odoo Addon

Install both addons from the `odoo_addons` path:

1. `field_service_road_reports`
2. `field_service_mobile_connector`

The `field_service_road_reports` addon is the section users see in Odoo. It adds the **Service Reports** app/menu, the report forms, the Field Service job buttons, and the review/quotation workflow.

The `field_service_mobile_connector` addon adds:

- mobile access tokens
- secure JSON endpoints under `/api/mobile/*`
- duplicate prevention and sync tracking fields on `field.service.road.report`

It does not replace the report module. It facilitates Android login, assigned Job download, offline report upload, photos, signatures, and retry-safe syncing into the `field.service.road.report` records.

## Android App

Open `android/TechnicianServiceReports` in Android Studio.

The app supports:

- technician login
- assigned Job list
- emergency `New Service Report`
- local drafts
- start/stop time tracking
- service report fields
- parts used
- touch-screen digital signatures
- pending-sync queue
- retry sync through WorkManager when network is available

Photos and signatures are distinct data paths. Signatures are captured from touch strokes and sent as base64 SVG payloads to Odoo binary signature fields.

## Mobile API

- `POST /api/mobile/login`
- `GET /api/mobile/jobs`
- `GET /api/mobile/jobs/<job_id>`
- `POST /api/mobile/service-reports`
- `POST /api/mobile/service-reports/<report_id>/submit`
- `POST /api/mobile/service-reports/<report_id>/attachments`
- `POST /api/mobile/service-reports/<report_id>/signatures`
- `POST /api/mobile/sync`

Offline reports are keyed by:

```text
mobile_external_id = device_id + local_report_uuid
```

Odoo updates an existing report when the same mobile ID is synced again.
