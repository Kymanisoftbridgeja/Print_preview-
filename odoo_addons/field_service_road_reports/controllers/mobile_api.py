import base64
import binascii

from odoo import fields, http
from odoo.exceptions import UserError
from odoo.http import request


class FieldServiceRoadReportMobileApi(http.Controller):
    @http.route(
        "/field_service_road_reports/mobile/reports",
        type="json",
        auth="user",
        methods=["POST"],
        csrf=False,
    )
    def submit_report(self, **kwargs):
        payload = self._json_payload(kwargs)
        report = self._create_or_update_report(payload)
        if payload.get("submit", True) and report.state != "completed":
            report.action_submit()
        return {
            "ok": True,
            "id": report.id,
            "name": report.name,
            "state": report.state,
            "sync_state": report.sync_state,
            "submitted_at": fields.Datetime.to_string(report.submitted_at)
            if report.submitted_at
            else False,
        }

    def _json_payload(self, kwargs):
        if hasattr(request, "get_json_data"):
            payload = request.get_json_data()
            if isinstance(payload, dict):
                return payload
        payload = getattr(request, "jsonrequest", None)
        if isinstance(payload, dict):
            return payload
        return kwargs or {}

    def _create_or_update_report(self, payload):
        Report = request.env["field.service.road.report"].sudo(False)
        external_number = payload.get("external_report_number") or payload.get("mobile_id")
        report = False
        if payload.get("id"):
            report = Report.browse(int(payload["id"])).exists()
        if not report and external_number:
            report = Report.search(
                [("external_report_number", "=", external_number)],
                limit=1,
            )

        values = self._report_values(payload)
        if external_number:
            values["external_report_number"] = external_number

        if report:
            report.with_context(service_report_skip_lock=True).write(values)
        else:
            report = Report.create(values)

        self._replace_report_lines(report, payload.get("line_ids") or payload.get("lines") or [])
        self._attach_files(report, payload.get("attachments") or [])
        return report

    def _report_values(self, payload):
        field_names = {
            "task_id",
            "report_origin",
            "sync_state",
            "customer_id",
            "billable_partner_id",
            "contact_person_id",
            "customer_address",
            "customer_phone",
            "customer_email",
            "report_company_name",
            "report_address",
            "site_contact",
            "purchase_order",
            "service_type",
            "service_date",
            "start_datetime",
            "stop_datetime",
            "driver_id",
            "vehicle",
            "location",
            "arrival_time",
            "departure_time",
            "odometer_start",
            "odometer_end",
            "equipment_name",
            "equipment_make",
            "equipment_model",
            "equipment_kva",
            "make_model_kva",
            "equipment_type",
            "equipment_serial",
            "equipment_type_serial",
            "load",
            "input_output_voltage",
            "ups_system_down",
            "battery_manufacturer_type",
            "battery_rating_quantity",
            "issue_reported",
            "problem_service_rendered",
            "defects_found",
            "diagnosis",
            "corrective_action_taken",
            "resolution",
            "work_performed",
            "recommendations",
            "customer_notes",
            "technicians_on_site",
            "service_status",
            "customer_name",
            "follow_up_required",
            "completed_on_site",
            "create_timesheet",
            "create_invoice",
            "driver_signature",
            "customer_signature",
            "driver_signature_name",
            "customer_signature_name",
            "driver_signed_at",
            "customer_signed_at",
            "source_pdf",
            "source_pdf_filename",
        }
        values = {name: payload[name] for name in field_names if name in payload}
        if not values.get("customer_id"):
            values["customer_id"] = self._resolve_customer(payload).id
        if not values.get("driver_id"):
            values["driver_id"] = request.env.user.id
        values.setdefault("report_origin", "job" if values.get("task_id") else "emergency")
        values.setdefault("sync_state", "synced")
        values.setdefault("state", "completed")
        return values

    def _resolve_customer(self, payload):
        Partner = request.env["res.partner"].sudo(False)
        customer = Partner.browse(int(payload["customer_id"])).exists() if payload.get("customer_id") else False
        if customer:
            return customer
        email = payload.get("customer_email")
        if email:
            customer = Partner.search([("email", "=", email)], limit=1)
            if customer:
                return customer
        name = payload.get("report_company_name") or payload.get("customer_name")
        if name:
            return Partner.create(
                {
                    "name": name,
                    "email": email,
                    "phone": payload.get("customer_phone"),
                    "street": payload.get("customer_address") or payload.get("report_address"),
                }
            )
        raise UserError(
            "customer_id, customer_email, report_company_name, or customer_name is required"
        )

    def _replace_report_lines(self, report, lines):
        if not isinstance(lines, list):
            return
        report.actual_line_ids.with_context(service_report_skip_lock=True).unlink()
        for index, line in enumerate(lines):
            if not isinstance(line, dict):
                continue
            values = {
                "report_id": report.id,
                "sequence": line.get("sequence", (index + 1) * 10),
                "line_type": "actual",
                "name": line.get("name") or line.get("part_name") or "Part / Service",
                "serial_number": line.get("serial_number"),
                "quantity": line.get("quantity", 1.0),
                "price_unit": line.get("price_unit", 0.0),
                "invoiceable": line.get("invoiceable", True),
                "notes": line.get("notes"),
            }
            for optional_field in ("product_id", "uom_id"):
                if line.get(optional_field):
                    values[optional_field] = line[optional_field]
            request.env["field.service.road.report.line"].sudo(False).with_context(
                service_report_skip_lock=True
            ).create(values)

    def _attach_files(self, report, attachments):
        if not isinstance(attachments, list):
            return
        attachment_ids = []
        Attachment = request.env["ir.attachment"].sudo(False)
        for item in attachments:
            if not isinstance(item, dict) or not item.get("data"):
                continue
            try:
                base64.b64decode(item["data"], validate=True)
            except (binascii.Error, TypeError):
                continue
            attachment = Attachment.create(
                {
                    "name": item.get("filename") or item.get("name") or "Mobile attachment",
                    "datas": item["data"],
                    "mimetype": item.get("mimetype"),
                    "res_model": report._name,
                    "res_id": report.id,
                }
            )
            attachment_ids.append(attachment.id)
        if attachment_ids:
            report.with_context(service_report_skip_lock=True).write(
                {"attachment_ids": [(6, 0, attachment_ids)]}
            )
