# Field Service Road Reports

Odoo 19 addon for recording driver or technician service reports from the road.

The module adds a service-report workflow on top of Odoo Field Service.

Backend users create the normal Field Service job and assign it to a technician.
The technician opens the assigned job and clicks **Start Service Report**. The
report is linked to the job, customer, and technician.

Technicians also have **Service Reports > My Reports** to review their own
reports. Reports are not started as standalone records; they are started from
the assigned Field Service job.

Each report stores the same core rows from the PowerTech PDF: date/arrival time,
company name, address, make/model/KVA, equipment type/serial, load, input/output
voltage, UPS system down, battery manufacturer/type, battery rating/quantity,
problem/service rendered, defects found, corrective action, recommendations,
technicians on-site, service status/departure time, customer name, and new parts.

Technicians can complete draft reports and click **Submit for Review**. Submitted
reports are locked for technicians. Reviewers can approve or send reports back for
correction from the Field Service job. Only approved reports can create
quotations, also from the Field Service job.

The workflow states are **Draft**, **Submitted / Waiting for Review**,
**Approved**, **Rejected / Needs Correction**, and **Quotation Created**.

Security groups:

- `Service Report Technician`
- `Service Report Reviewer / Manager`

Core dependencies are `industry_fsm`, `sale_timesheet`, `sale_management`,
`account`, and `mail`.

Install the addon, assign the security groups, create a Field Service job, and
use **Start Service Report** on the job.

## Mobile sync endpoint

The addon exposes a JSON route for the mobile app:

`POST /field_service_road_reports/mobile/reports`

The request must be authenticated as an Odoo user. The endpoint creates or
updates a `field.service.road.report`, replaces its mobile-supplied line items,
stores base64 attachments, and submits the report by default.

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

Use `submit: false` to leave the created report completed but not submitted.
Use `external_report_number` or `mobile_id` for retry-safe updates.
