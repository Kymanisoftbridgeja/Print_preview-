# Field Service Road Reports

Odoo 19 addon for recording driver or technician service reports from the road.

The module adds a service-report workflow on top of Odoo Field Service.

Backend users create the normal Field Service job, select the customer/contact,
service type, and products/services/items to bring, then assign it to a
technician. Saving the job automatically creates the linked Service Report.

Technicians also have **Service Reports > My Reports** to review their own
reports. Reports are not started as standalone records; they are started from
the assigned Field Service job.

Each report stores the same core rows from the PowerTech PDF: date/arrival time,
company name, address, make/model/KVA, equipment type/serial, load, input/output
voltage, UPS system down, battery manufacturer/type, battery rating/quantity,
problem/service rendered, defects found, corrective action, recommendations,
technicians on-site, service status/departure time, customer name, and new parts.

Technicians open the linked Service Report from the mobile app, complete the
actual work details, signatures, photos, and actual parts used, then mark the
report completed. Reviewers approve completed reports from Odoo.

The business workflow states are **Assigned**, **In Progress**, **Completed**,
and **Approved**. Mobile sync statuses remain separate: local draft, pending sync,
synced, and sync failed.

Service Reports do not auto-create quotations or invoices. Reviewers manually
link a quotation/sales order, invoice, or manual quotation/invoice number on the
report.

Security groups:

- `Service Report Technician`
- `Service Report Reviewer / Manager`

Core dependencies are `industry_fsm`, `sale_timesheet`, `sale_management`,
`account`, and `mail`.

Install the addon, assign the security groups, and create a Field Service job
with a customer. The linked Service Report appears immediately in Service
Reports.

## Mobile sync endpoint

The addon exposes a JSON route for the mobile app:

`POST /field_service_road_reports/mobile/reports`

The request must be authenticated as an Odoo user. The endpoint creates or
updates a `field.service.road.report`, replaces its mobile-supplied actual items
used, stores base64 attachments, and completes the report by default.

Example payload:

```json
{
  "mobile_id": "device-report-001",
  "task_id": 42,
  "customer_id": 17,
  "service_date": "2026-05-29",
  "report_company_name": "Customer Ltd",
  "problem_service_rendered": "UPS inspection completed",
  "corrective_action_taken": "Replaced weak battery links",
  "completed_on_site": true,
  "follow_up_required": false,
  "customer_signature": "base64-signature",
  "customer_signature_name": "Jane Customer",
  "lines": [
    {
      "name": "Battery link",
      "serial_number": "BL-001",
      "quantity": 2,
      "price_unit": 15.0,
      "invoiceable": true
    }
  ],
  "attachments": [
    {
      "filename": "site-photo.jpg",
      "mimetype": "image/jpeg",
      "data": "base64-file-data"
    }
  ]
}
```

Use `submit: false` to save without marking the report completed.
Use `external_report_number` or `mobile_id` for retry-safe updates.
