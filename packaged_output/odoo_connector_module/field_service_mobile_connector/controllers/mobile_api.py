import json

from odoo import fields, http
from odoo.exceptions import AccessError
from odoo.http import request


class FieldServiceMobileApi(http.Controller):
    def _json(self, payload, status=200):
        return request.make_json_response(payload, status=status)

    def _body(self):
        return json.loads(request.httprequest.get_data(as_text=True) or "{}")

    def _user_env(self):
        auth = request.httprequest.headers.get("Authorization", "")
        token = auth.removeprefix("Bearer ").strip()
        user = request.env["field.service.mobile.token"].authenticate(token)
        return request.env(user=user)

    @http.route("/api/mobile/login", type="http", auth="none", methods=["POST"], csrf=False)
    def login(self):
        data = self._body()
        db = data.get("db") or request.session.db
        uid = request.session.authenticate(db, data.get("login"), data.get("password"))
        if not uid:
            return self._json({"error": "Invalid login"}, status=401)
        user = request.env["res.users"].sudo().browse(uid)
        token, expires_at = request.env["field.service.mobile.token"].sudo().issue(user)
        return self._json(
            {
                "access_token": token,
                "expires_at": fields.Datetime.to_string(expires_at),
                "user": {"id": user.id, "name": user.name, "login": user.login},
            }
        )

    @http.route("/api/mobile/jobs", type="http", auth="none", methods=["GET"], csrf=False)
    def jobs(self):
        env = self._user_env()
        return self._json({"jobs": [self._job_payload(job) for job in self._assigned_jobs(env)]})

    @http.route("/api/mobile/jobs/<int:job_id>", type="http", auth="none", methods=["GET"], csrf=False)
    def job_detail(self, job_id):
        env = self._user_env()
        job = env["project.task"].browse(job_id).exists()
        if not job or job not in self._assigned_jobs(env):
            raise AccessError("Job is not assigned to this technician.")
        payload = self._job_payload(job)
        report = env["field.service.road.report"].search(
            [("task_id", "=", job.id), ("driver_id", "=", env.user.id)],
            order="service_date desc, id desc",
            limit=1,
        )
        payload["report"] = self._report_payload(report) if report else None
        return self._json(payload)

    @http.route("/api/mobile/service-reports", type="http", auth="none", methods=["POST"], csrf=False)
    def upsert_report(self):
        env = self._user_env()
        report = self._upsert_report(env, self._body())
        return self._json({"report": self._report_payload(report)})

    @http.route(
        "/api/mobile/service-reports/<int:report_id>/submit",
        type="http",
        auth="none",
        methods=["POST"],
        csrf=False,
    )
    def submit_report(self, report_id):
        env = self._user_env()
        report = env["field.service.road.report"].browse(report_id).exists()
        if not report or report.driver_id != env.user:
            raise AccessError("Report is not owned by this technician.")
        report.action_submit()
        return self._json({"report": self._report_payload(report)})

    @http.route(
        "/api/mobile/service-reports/<int:report_id>/attachments",
        type="http",
        auth="none",
        methods=["POST"],
        csrf=False,
    )
    def attachments(self, report_id):
        env = self._user_env()
        report = env["field.service.road.report"].browse(report_id).exists()
        if not report or report.driver_id != env.user:
            raise AccessError("Report is not owned by this technician.")
        attachment_ids = []
        for item in self._body().get("attachments", []):
            attachment = env["ir.attachment"].create(
                {
                    "name": item.get("filename") or "service-photo.jpg",
                    "datas": item.get("content_base64"),
                    "mimetype": item.get("mime_type") or "image/jpeg",
                    "res_model": "field.service.road.report",
                    "res_id": report.id,
                }
            )
            attachment_ids.append(attachment.id)
        report.with_context(service_report_skip_lock=True).write(
            {"attachment_ids": [(4, attachment_id) for attachment_id in attachment_ids]}
        )
        return self._json({"attachment_ids": attachment_ids})

    @http.route(
        "/api/mobile/service-reports/<int:report_id>/signatures",
        type="http",
        auth="none",
        methods=["POST"],
        csrf=False,
    )
    def signatures(self, report_id):
        env = self._user_env()
        report = env["field.service.road.report"].browse(report_id).exists()
        if not report or report.driver_id != env.user:
            raise AccessError("Report is not owned by this technician.")
        data = self._body()
        report.with_context(service_report_skip_lock=True).write(
            {
                "customer_signature": data.get("customer_signature_base64"),
                "driver_signature": data.get("technician_signature_base64"),
                "customer_signature_name": data.get("customer_name"),
                "driver_signature_name": data.get("technician_name") or env.user.name,
                "customer_signed_at": data.get("signature_datetime") or fields.Datetime.now(),
                "driver_signed_at": data.get("signature_datetime") or fields.Datetime.now(),
            }
        )
        return self._json({"report": self._report_payload(report)})

    @http.route("/api/mobile/sync", type="http", auth="none", methods=["POST"], csrf=False)
    def sync(self):
        env = self._user_env()
        results = []
        for item in self._body().get("reports", []):
            try:
                report = self._upsert_report(env, item)
                if item.get("submit"):
                    report.action_submit()
                results.append(
                    {
                        "mobile_external_id": item.get("mobile_external_id"),
                        "odoo_id": report.id,
                        "report_number": report.name,
                        "status": "synced",
                    }
                )
            except Exception as exc:
                results.append(
                    {
                        "mobile_external_id": item.get("mobile_external_id"),
                        "status": "sync_failed",
                        "error": str(exc),
                    }
                )
        return self._json({"results": results})

    def _assigned_jobs(self, env):
        Task = env["project.task"]
        domain = [("is_fsm", "=", True)] if "is_fsm" in Task._fields else []
        if "user_ids" in Task._fields:
            domain.append(("user_ids", "in", env.user.id))
        elif "user_id" in Task._fields:
            domain.append(("user_id", "=", env.user.id))
        return Task.search(domain, order="date_deadline asc, id desc")

    def _upsert_report(self, env, data):
        Report = env["field.service.road.report"].with_context(service_report_skip_lock=True)
        report = Report.search([("mobile_external_id", "=", data.get("mobile_external_id"))], limit=1)
        values = self._report_values(env, data)
        values["line_ids"] = [(5, 0, 0)] + [(0, 0, self._line_values(line)) for line in data.get("parts", [])]
        if report:
            report.write(values)
        else:
            report = Report.create(values)
        return report

    def _report_values(self, env, data):
        customer_id = data.get("customer_id")
        if not customer_id and data.get("customer_name"):
            customer_id = env["res.partner"].create({"name": data["customer_name"]}).id
        values = {
            "task_id": data.get("job_id") or False,
            "report_origin": "job" if data.get("job_id") else "emergency",
            "customer_id": customer_id,
            "driver_id": env.user.id,
            "service_date": data.get("service_date") or fields.Date.today(),
            "start_datetime": data.get("arrival_time") or False,
            "stop_datetime": data.get("departure_time") or False,
            "vehicle": data.get("vehicle"),
            "purchase_order": data.get("po_reference"),
            "service_type": data.get("service_type"),
            "external_report_number": data.get("original_report_number"),
            "report_address": data.get("address"),
            "location": data.get("address"),
            "equipment_make": data.get("make"),
            "equipment_model": data.get("model"),
            "equipment_kva": data.get("kva"),
            "equipment_type": data.get("equipment_type"),
            "equipment_serial": data.get("serial_number"),
            "load": data.get("load"),
            "input_output_voltage": " / ".join(
                value for value in [data.get("input_voltage"), data.get("output_voltage")] if value
            ),
            "ups_system_down": "Yes" if data.get("system_down") else "No",
            "battery_manufacturer_type": " / ".join(
                value for value in [data.get("battery_manufacturer"), data.get("battery_type")] if value
            ),
            "battery_rating_quantity": " / ".join(
                value for value in [data.get("battery_rating"), str(data.get("battery_quantity") or "")] if value
            ),
            "problem_service_rendered": data.get("problem_reported"),
            "defects_found": data.get("defects_found"),
            "corrective_action_taken": data.get("corrective_action"),
            "recommendations": data.get("recommendations"),
            "service_status": data.get("status_of_service"),
            "customer_signature": data.get("customer_signature_base64"),
            "driver_signature": data.get("technician_signature_base64"),
            "customer_signature_name": data.get("customer_name"),
            "driver_signature_name": data.get("technician_name") or env.user.name,
            "mobile_external_id": data.get("mobile_external_id"),
            "mobile_last_sync_at": fields.Datetime.now(),
        }
        if data.get("state") in ("draft", "in_progress", "completed", "submitted"):
            values["state"] = "completed" if data.get("state") == "submitted" else data.get("state")
        return values

    def _line_values(self, line):
        return {
            "name": line.get("part_name") or "Part",
            "serial_number": line.get("serial_number"),
            "quantity": line.get("quantity") or 1,
            "invoiceable": bool(line.get("invoiceable", True)),
            "notes": line.get("notes"),
        }

    def _job_payload(self, job):
        partner = job.partner_id
        company = partner.parent_id or partner
        contact = partner if partner and partner != company else False
        return {
            "id": job.id,
            "job_number": job.display_name,
            "customer_id": partner.id,
            "company_name": company.name if company else "",
            "contact_name": contact.name if contact else "",
            "address": partner.contact_address if partner and "contact_address" in partner._fields else "",
            "scheduled_date": fields.Datetime.to_string(job.planned_date_begin)
            if "planned_date_begin" in job._fields and job.planned_date_begin
            else "",
            "service_type": "",
            "status": "Completed" if getattr(job, "fsm_done", False) else "Assigned",
            "description": job.description or "",
        }

    def _report_payload(self, report):
        return {
            "id": report.id,
            "report_number": report.name,
            "job_id": report.task_id.id,
            "mobile_external_id": report.mobile_external_id,
            "state": report.state,
            "labor_hours": report.labor_hours,
        }
