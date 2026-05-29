import inspect
import json
from datetime import datetime, time, timezone

from odoo import fields, http
from odoo.exceptions import AccessError, UserError, ValidationError
from odoo.http import request
from odoo.service import db as db_service


class FieldServiceMobileApi(http.Controller):
    def _json(self, payload, status=200):
        return request.make_json_response(payload, status=status)

    def _success(self, payload=None, **extra):
        data = {"success": True}
        if payload:
            data.update(payload)
        data.update(extra)
        return self._json(data)

    def _failure(self, error, status=400):
        return self._json({"success": False, "error": str(error)}, status=status)

    def _body(self):
        return json.loads(request.httprequest.get_data(as_text=True) or "{}")

    def _user_env(self):
        auth = request.httprequest.headers.get("Authorization", "")
        token = auth.removeprefix("Bearer ").strip()
        user = request.env["field.service.mobile.token"].authenticate(token)
        return request.env(user=user)

    @http.route("/api/mobile/login", type="http", auth="none", methods=["POST"], csrf=False)
    def login(self):
        try:
            data = self._body()
            db = data.get("db") or request.session.db or self._single_database()
            if not db:
                return self._failure(
                    "Database name is required because this Odoo server has multiple databases.",
                    status=400,
                )
            uid = self._authenticate_session(db, data.get("login"), data.get("password"))
            if not uid:
                return self._failure("Authentication failed", status=401)
            user = request.env["res.users"].sudo().browse(uid)
            token, expires_at = request.env["field.service.mobile.token"].sudo().issue(user)
            return self._json(
                {
                    "success": True,
                    "access_token": token,
                    "expires_at": fields.Datetime.to_string(expires_at),
                    "user": {"id": user.id, "name": user.name, "login": user.login},
                }
            )
        except Exception as exc:
            return self._failure(exc, status=500)

    def _single_database(self):
        databases = db_service.list_dbs(force=True)
        return databases[0] if len(databases) == 1 else False

    def _authenticate_session(self, db, login, password):
        if not login or not password:
            return False
        if db:
            request.session.db = db
        authenticate = request.session.authenticate
        credential = {"login": login, "password": password, "type": "password"}
        parameter_count = len(inspect.signature(authenticate).parameters)
        if parameter_count == 2:
            auth_info = authenticate(request.env, credential)
        else:
            auth_info = authenticate(db, login, password)
        if isinstance(auth_info, dict):
            return auth_info.get("uid") or request.session.uid
        return auth_info or request.session.uid

    @http.route("/api/mobile/jobs", type="http", auth="none", methods=["GET"], csrf=False)
    def jobs(self):
        try:
            env = self._user_env()
            jobs = [self._report_job_payload(report, env) for report in self._assigned_reports(env)]
            return self._json(
                {
                    "success": True,
                    "jobs": jobs,
                    "message": "No assigned Service Reports found for this technician." if not jobs else "",
                }
            )
        except Exception as exc:
            return self._failure(exc, status=401 if isinstance(exc, AccessError) else 500)

    @http.route("/api/mobile/jobs/<int:job_id>", type="http", auth="none", methods=["GET"], csrf=False)
    def job_detail(self, job_id):
        try:
            env = self._user_env()
            report = self._report_from_mobile_id(env, job_id)
            payload = self._report_job_payload(report, env)
            payload["report"] = self._report_payload(report, full=True)
            return self._success(payload)
        except Exception as exc:
            return self._failure(exc, status=400)

    @http.route(
        "/api/mobile/jobs/<int:job_id>/service-report",
        type="http",
        auth="none",
        methods=["GET"],
        csrf=False,
    )
    def job_service_report(self, job_id):
        try:
            env = self._user_env()
            report = self._report_from_mobile_id(env, job_id)
            return self._success({"report": self._report_payload(report, full=True)})
        except Exception as exc:
            return self._failure(exc, status=400)

    @http.route(
        "/api/mobile/jobs/<int:job_id>/start",
        type="http",
        auth="none",
        methods=["POST"],
        csrf=False,
    )
    def start_job(self, job_id):
        try:
            env = self._user_env()
            report = self._report_from_mobile_id(env, job_id)
            job = report.task_id
            if report.state in ("submitted", "approved", "quotation_created"):
                return self._success(
                    {
                        "job_id": job.id if job else None,
                        "report_id": report.id,
                        "state": report.state,
                        "sync_status": "synced",
                        "message": "Service report is already submitted for backend review.",
                        "report": self._report_payload(report, full=True),
                    }
                )
            if report.state not in ("draft", "assigned", "rejected", "completed"):
                raise UserError("Only draft, assigned, rejected, or completed reports can be started.")
            now = fields.Datetime.now()
            report.with_context(service_report_skip_lock=True).write(
                {
                    "state": "in_progress",
                    "start_datetime": now,
                    "arrival_time": self._float_time_from_datetime(report, now),
                    "mobile_last_sync_at": now,
                }
            )
            return self._success(
                {
                    "job_id": job.id if job else None,
                    "report_id": report.id,
                    "state": report.state,
                    "sync_status": "synced",
                    "message": "Job started successfully",
                    "report": self._report_payload(report, full=True),
                }
            )
        except Exception as exc:
            return self._failure(exc, status=400)

    @http.route(
        "/api/mobile/jobs/<int:job_id>/stop",
        type="http",
        auth="none",
        methods=["POST"],
        csrf=False,
    )
    def stop_job(self, job_id):
        try:
            env = self._user_env()
            report = self._report_from_mobile_id(env, job_id)
            job = report.task_id
            if report.state in ("submitted", "approved", "quotation_created"):
                return self._success(
                    {
                        "job_id": job.id if job else None,
                        "report_id": report.id,
                        "state": report.state,
                        "sync_status": "synced",
                        "message": "Service report is already submitted for backend review.",
                        "report": self._report_payload(report, full=True),
                    }
                )
            if report.state != "in_progress":
                raise UserError("Only in-progress reports can be stopped.")
            now = fields.Datetime.now()
            values = {
                "state": "completed",
                "stop_datetime": now,
                "departure_time": self._float_time_from_datetime(report, now),
                "mobile_last_sync_at": now,
            }
            report.with_context(service_report_skip_lock=True).write(values)
            if job and "fsm_done" in job._fields:
                job.write({"fsm_done": True})
            return self._success(
                {
                    "job_id": job.id if job else None,
                    "report_id": report.id,
                    "state": report.state,
                    "labor_hours": report.labor_hours,
                    "sync_status": "synced",
                    "message": "Job stopped successfully",
                    "report": self._report_payload(report, full=True),
                }
            )
        except Exception as exc:
            return self._failure(exc, status=400)

    @http.route("/api/mobile/service-reports", type="http", auth="none", methods=["POST"], csrf=False)
    def upsert_report(self):
        try:
            env = self._user_env()
            data = self._body()
            report = self._upsert_report(env, data)
            if data.get("submit") and report.state not in ("submitted", "approved", "quotation_created"):
                report.action_submit()
            return self._success({"report": self._report_payload(report, full=True)})
        except Exception as exc:
            return self._failure(exc, status=400)

    @http.route(
        "/api/mobile/service-reports/<int:report_id>/submit",
        type="http",
        auth="none",
        methods=["POST"],
        csrf=False,
    )
    def submit_report(self, report_id):
        try:
            env = self._user_env()
            report = env["field.service.road.report"].browse(report_id).exists()
            if not report:
                raise UserError("Service report not found.")
            if report.driver_id != env.user:
                raise AccessError("Access denied for technician.")
            if report.state in ("submitted", "approved", "quotation_created"):
                return self._success(
                    {
                        "report": self._report_payload(report, full=True),
                        "sync_status": "synced",
                        "message": "Service report is already submitted for backend review.",
                    }
                )
            data = self._body()
            if data:
                report = self._upsert_report(env, data, report=report)
            if report.state not in ("completed", "rejected", "draft"):
                report.with_context(service_report_skip_lock=True).write({"state": "completed"})
            report.action_submit()
            return self._success(
                {
                    "report": self._report_payload(report, full=True),
                    "sync_status": "synced",
                    "message": "Service report submitted successfully",
                }
            )
        except Exception as exc:
            return self._failure(exc, status=400)

    @http.route(
        "/api/mobile/service-reports/<int:report_id>/attachments",
        type="http",
        auth="none",
        methods=["POST"],
        csrf=False,
    )
    def attachments(self, report_id):
        try:
            env = self._user_env()
            report = env["field.service.road.report"].browse(report_id).exists()
            if not report:
                raise UserError("Service report not found.")
            if report.driver_id != env.user:
                raise AccessError("Access denied for technician.")
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
            return self._success({"attachment_ids": attachment_ids})
        except Exception as exc:
            return self._failure(exc, status=400)

    @http.route(
        "/api/mobile/service-reports/<int:report_id>/signatures",
        type="http",
        auth="none",
        methods=["POST"],
        csrf=False,
    )
    def signatures(self, report_id):
        try:
            env = self._user_env()
            report = env["field.service.road.report"].browse(report_id).exists()
            if not report:
                raise UserError("Service report not found.")
            if report.driver_id != env.user:
                raise AccessError("Access denied for technician.")
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
            return self._success({"report": self._report_payload(report, full=True)})
        except Exception as exc:
            return self._failure(exc, status=400)

    @http.route("/api/mobile/sync", type="http", auth="none", methods=["POST"], csrf=False)
    def sync(self):
        try:
            env = self._user_env()
        except Exception as exc:
            return self._failure(exc, status=401)
        results = []
        for item in self._body().get("reports", []):
            try:
                report = self._upsert_report(env, item)
                if item.get("submit") and report.state not in ("submitted", "approved", "quotation_created"):
                    if report.state not in ("completed", "rejected", "draft"):
                        report.with_context(service_report_skip_lock=True).write({"state": "completed"})
                    report.action_submit()
                results.append(
                    {
                        "mobile_external_id": item.get("mobile_external_id"),
                        "odoo_id": report.id,
                        "report_number": report.name,
                        "state": report.state,
                        "status": "synced",
                        "error": None,
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
        return self._json({"success": True, "results": results})

    def _assigned_reports(self, env):
        Report = env["field.service.road.report"].sudo()
        reports = Report.search([], order="service_date desc, id desc")
        if env.user.has_group("field_service_road_reports.group_service_report_manager"):
            return reports
        return reports.filtered(lambda report: self._report_assigned_to_user(report, env.user))

    def _assigned_report(self, env, report_id):
        report = env["field.service.road.report"].sudo().browse(report_id).exists()
        if not report:
            return report
        if not self._report_assigned_to_user(report, env.user) and not env.user.has_group(
            "field_service_road_reports.group_service_report_manager"
        ):
            raise AccessError("Access denied for technician.")
        return report

    def _report_from_mobile_id(self, env, record_id):
        report = env["field.service.road.report"].sudo().browse(record_id).exists()
        if report and (
            self._report_assigned_to_user(report, env.user)
            or env.user.has_group("field_service_road_reports.group_service_report_manager")
        ):
            return report
        report_access_error = AccessError("Access denied for technician.") if report else False
        try:
            job = self._assigned_job(env, record_id)
        except Exception:
            if report_access_error:
                raise report_access_error
            raise
        return self._ensure_report_for_job(env, job)

    def _report_assigned_to_user(self, report, user):
        if report.driver_id == user:
            return True
        return bool(report.task_id and self._job_assigned_to_user(report.task_id, user))

    def _assigned_jobs(self, env):
        Task = env["project.task"].sudo()
        domain = [("is_fsm", "=", True)] if "is_fsm" in Task._fields else []
        jobs = Task.search(domain, order="date_deadline asc, id desc")
        if env.user.has_group("field_service_road_reports.group_service_report_manager"):
            return jobs
        assigned = jobs.filtered(lambda job: self._job_assigned_to_user(job, env.user))
        report_jobs = env["field.service.road.report"].sudo().search(
            [("driver_id", "=", env.user.id), ("task_id", "!=", False)]
        ).mapped("task_id")
        return assigned | report_jobs

    def _assigned_job(self, env, job_id):
        job = env["project.task"].sudo().browse(job_id).exists()
        if not job:
            raise UserError("Job not found.")
        if job not in self._assigned_jobs(env):
            raise AccessError("Access denied for technician.")
        return job

    def _job_assigned_to_user(self, job, user):
        for field_name in ("user_ids", "assigned_user_ids"):
            if field_name in job._fields and user in job[field_name]:
                return True
        for field_name in ("user_id", "fsm_user_id", "responsible_id"):
            if field_name in job._fields and job[field_name] == user:
                return True
        employee = user.employee_id if "employee_id" in user._fields else False
        if employee:
            for field_name in ("employee_id", "technician_id"):
                if field_name in job._fields and job[field_name] == employee:
                    return True
        return False

    def _report_for_job(self, env, job):
        return env["field.service.road.report"].sudo().search(
            [("task_id", "=", job.id), ("driver_id", "=", env.user.id)],
            order="service_date desc, id desc",
            limit=1,
        )

    def _ensure_report_for_job(self, env, job):
        report = self._report_for_job(env, job)
        if report:
            return report
        if not job.partner_id:
            raise ValidationError("Missing required field: customer_id")
        values = job._prepare_road_report_values() if hasattr(job, "_prepare_road_report_values") else {}
        values.update(
            {
                "task_id": job.id,
                "report_origin": "job",
                "customer_id": job.partner_id.id,
                "driver_id": env.user.id,
                "state": "assigned",
            }
        )
        return env["field.service.road.report"].sudo().with_context(service_report_skip_lock=True).create(values)

    def _upsert_report(self, env, data, report=None):
        Report = env["field.service.road.report"].sudo().with_context(service_report_skip_lock=True)
        if not report and data.get("id"):
            report = Report.browse(data["id"]).exists()
        if not report and data.get("mobile_external_id"):
            report = Report.search([("mobile_external_id", "=", data.get("mobile_external_id"))], limit=1)
        if not report and data.get("job_id"):
            job = self._assigned_job(env, data["job_id"])
            report = self._report_for_job(env, job)
        if report and report.state in ("submitted", "approved", "quotation_created"):
            return report
        values = self._report_values(env, data)
        if "parts" in data or "lines" in data:
            lines = data.get("parts") if "parts" in data else data.get("lines")
            values["line_ids"] = [(5, 0, 0)] + [(0, 0, self._line_values(line)) for line in (lines or [])]
        if report:
            report.write(values)
        else:
            report = Report.create(values)
        return report

    def _report_values(self, env, data):
        job = env["project.task"].browse(data["job_id"]).exists() if data.get("job_id") else False
        customer_id = data.get("customer_id")
        if not customer_id and job and job.partner_id:
            customer_id = job.partner_id.id
        if not customer_id and data.get("customer_name"):
            customer_id = env["res.partner"].create({"name": data["customer_name"]}).id
        if not customer_id:
            raise ValidationError("Missing required field: customer_id")

        service_date = data.get("service_date") or fields.Date.today()
        arrival_dt = self._datetime_from_mobile(data.get("arrival_time"), service_date)
        departure_dt = self._datetime_from_mobile(data.get("departure_time"), service_date)
        values = {
            "task_id": data.get("job_id") or False,
            "report_origin": "job" if data.get("job_id") else "emergency",
            "customer_id": customer_id,
            "driver_id": env.user.id,
            "service_date": self._date_from_mobile(service_date),
            "start_datetime": arrival_dt,
            "stop_datetime": departure_dt,
            "vehicle": data.get("vehicle"),
            "purchase_order": data.get("po_reference"),
            "service_type": data.get("service_type"),
            "external_report_number": data.get("original_report_number"),
            "report_company_name": data.get("company_name"),
            "customer_name": data.get("customer_name") or data.get("contact_name"),
            "site_contact": data.get("contact_name"),
            "report_address": data.get("address"),
            "customer_address": data.get("address"),
            "location": data.get("address"),
            "equipment_make": data.get("make"),
            "equipment_model": data.get("model"),
            "equipment_kva": data.get("kva"),
            "make_model_kva": self._join_pair(data.get("make"), data.get("model"), data.get("kva")),
            "equipment_type": data.get("equipment_type"),
            "equipment_serial": data.get("serial_number"),
            "equipment_type_serial": self._join_pair(data.get("equipment_type"), data.get("serial_number")),
            "load": data.get("load"),
            "input_output_voltage": self._join_pair(data.get("input_voltage"), data.get("output_voltage")),
            "ups_system_down": "Yes" if data.get("system_down") else "No",
            "battery_manufacturer_type": self._join_pair(data.get("battery_manufacturer"), data.get("battery_type")),
            "battery_rating_quantity": self._join_pair(data.get("battery_rating"), data.get("battery_quantity")),
            "problem_service_rendered": data.get("problem_reported"),
            "defects_found": data.get("defects_found"),
            "corrective_action_taken": data.get("corrective_action"),
            "recommendations": data.get("recommendations"),
            "technicians_on_site": data.get("technicians_on_site"),
            "service_status": data.get("status_of_service"),
            "customer_signature": data.get("customer_signature_base64"),
            "driver_signature": data.get("technician_signature_base64"),
            "customer_signature_name": data.get("customer_name"),
            "driver_signature_name": data.get("technician_name") or env.user.name,
            "mobile_external_id": data.get("mobile_external_id"),
            "mobile_last_sync_at": fields.Datetime.now(),
        }
        if arrival_dt:
            values["arrival_time"] = self._float_time_from_datetime(env["field.service.road.report"], arrival_dt)
        if departure_dt:
            values["departure_time"] = self._float_time_from_datetime(env["field.service.road.report"], departure_dt)
        state = data.get("state")
        if state in ("draft", "assigned", "in_progress", "completed", "rejected"):
            values["state"] = state
        elif state == "submitted":
            values["state"] = "completed"
        return values

    def _line_values(self, line):
        return {
            "name": line.get("part_name") or line.get("name") or "Part",
            "serial_number": line.get("serial_number"),
            "quantity": line.get("quantity") or 1,
            "invoiceable": bool(line.get("invoiceable", True)),
            "notes": line.get("notes"),
        }

    def _report_job_payload(self, report, env=None):
        job = report.task_id
        partner = report.customer_id if report.customer_id else (job.partner_id if job and job.partner_id else False)
        company = report.billable_partner_id or (partner.parent_id or partner if partner else False)
        contact = report.contact_person_id or (partner if partner and partner != company else False)
        scheduled_date = ""
        if report.service_date:
            scheduled_date = fields.Date.to_string(report.service_date)
        elif job and "planned_date_begin" in job._fields and job.planned_date_begin:
            scheduled_date = fields.Datetime.to_string(job.planned_date_begin)
        job_name = (job.display_name or job.name) if job else ""
        report_name = report.name or f"Service Report #{report.id}"
        return {
            "id": job.id if job else report.id,
            "report_id": report.id,
            "job_number": f"{report_name} - {job_name}" if job_name else report_name,
            "customer_id": partner.id if partner else None,
            "company_name": report.report_company_name or (company.name if company else ""),
            "contact_name": report.site_contact or (contact.name if contact else ""),
            "address": report.report_address
            or report.customer_address
            or report.location
            or (partner.contact_address if partner and "contact_address" in partner._fields else ""),
            "scheduled_date": scheduled_date,
            "service_type": report.service_type or "",
            "status": report.state,
            "sync_status": "synced",
            "report_status": report.state,
            "description": report.problem_service_rendered or (job.description if job else ""),
        }

    def _job_payload(self, job, env=None):
        partner = job.partner_id if job.partner_id else False
        company = (partner.parent_id or partner) if partner else False
        contact = partner if partner and partner != company else False
        report = self._report_for_job(env or job.env, job)
        return {
            "id": job.id,
            "job_number": job.display_name or job.name or f"Job #{job.id}",
            "customer_id": partner.id if partner else None,
            "company_name": company.name if company else "",
            "contact_name": contact.name if contact else "",
            "address": partner.contact_address if partner and "contact_address" in partner._fields else "",
            "scheduled_date": fields.Datetime.to_string(job.planned_date_begin)
            if "planned_date_begin" in job._fields and job.planned_date_begin
            else "",
            "service_type": "",
            "status": report.state if report else ("completed" if getattr(job, "fsm_done", False) else "assigned"),
            "sync_status": "synced",
            "report_status": report.state if report else "new",
            "description": job.description or "",
        }

    def _report_payload(self, report, full=False):
        base = {
            "id": report.id,
            "name": report.name,
            "report_number": report.name,
            "job_id": report.task_id.id if report.task_id else None,
            "mobile_external_id": report.mobile_external_id,
            "state": report.state,
            "labor_hours": report.labor_hours,
        }
        if not full:
            return base

        input_voltage, output_voltage = self._split_pair(report.input_output_voltage)
        battery_manufacturer, battery_type = self._split_pair(report.battery_manufacturer_type)
        battery_rating, battery_quantity = self._split_pair(report.battery_rating_quantity)
        company = report.billable_partner_id or (report.customer_id.parent_id or report.customer_id)
        contact = report.contact_person_id or (report.customer_id if report.customer_id != company else False)
        base.update(
            {
                "company_name": report.report_company_name or (company.name if company else ""),
                "contact_name": report.site_contact or (contact.name if contact else ""),
                "customer_id": report.customer_id.id if report.customer_id else None,
                "customer_name": report.customer_signature_name or report.customer_name or (report.customer_id.name if report.customer_id else ""),
                "address": report.report_address or report.customer_address or report.location or "",
                "service_date": fields.Date.to_string(report.service_date) if report.service_date else "",
                "arrival_time": self._time_string(report, "start_datetime", "arrival_time"),
                "departure_time": self._time_string(report, "stop_datetime", "departure_time"),
                "technician": report.driver_id.name if report.driver_id else "",
                "technician_name": report.driver_signature_name or (report.driver_id.name if report.driver_id else ""),
                "vehicle": report.vehicle or "",
                "po_reference": report.purchase_order or "",
                "service_type": report.service_type or "",
                "original_report_number": report.external_report_number or "",
                "make": report.equipment_make or "",
                "model": report.equipment_model or "",
                "kva": report.equipment_kva or "",
                "equipment_type": report.equipment_type or "",
                "serial_number": report.equipment_serial or "",
                "load": report.load or "",
                "input_voltage": input_voltage,
                "output_voltage": output_voltage,
                "ups_system_down": (report.ups_system_down or "").lower() in ("yes", "true", "1"),
                "battery_manufacturer": battery_manufacturer,
                "battery_type": battery_type,
                "battery_rating": battery_rating,
                "battery_quantity": int(float(battery_quantity or 0)),
                "problem_reported": report.problem_service_rendered or "",
                "defects_found": report.defects_found or "",
                "corrective_action": report.corrective_action_taken or "",
                "recommendations": report.recommendations or "",
                "technicians_on_site": report.technicians_on_site or "",
                "status_of_service": report.service_status or "",
                "lines": [
                    {
                        "part_name": line.name or "",
                        "serial_number": line.serial_number or "",
                        "quantity": line.quantity,
                        "invoiceable": line.invoiceable,
                        "notes": line.notes or "",
                    }
                    for line in report.line_ids
                ],
            }
        )
        return base

    def _join_pair(self, *values):
        return " / ".join(str(value) for value in values if value not in (None, False, ""))

    def _split_pair(self, value):
        if not value:
            return "", ""
        normalized = str(value).replace("|", "/")
        parts = [part.strip() for part in normalized.split("/", 1)]
        return (parts + [""])[:2]

    def _datetime_from_mobile(self, value, service_date):
        if not value:
            return False
        if isinstance(value, str) and len(value) <= 5 and ":" in value:
            service_date = fields.Date.to_date(self._date_from_mobile(service_date)) or fields.Date.today()
            hour, minute = value.split(":", 1)
            return fields.Datetime.to_string(datetime.combine(service_date, time(int(hour), int(minute[:2]))))
        if isinstance(value, str):
            normalized = value.strip()
            try:
                if normalized.endswith("Z"):
                    normalized = normalized[:-1] + "+00:00"
                normalized = normalized.replace("T", " ")
                parsed = datetime.fromisoformat(normalized)
                if parsed.tzinfo:
                    parsed = parsed.astimezone(timezone.utc).replace(tzinfo=None)
                return fields.Datetime.to_string(parsed)
            except ValueError:
                for pattern in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%H:%M:%S"):
                    try:
                        parsed = datetime.strptime(normalized, pattern)
                        if pattern.startswith("%H"):
                            parsed = datetime.combine(fields.Date.to_date(self._date_from_mobile(service_date)), parsed.time())
                        return fields.Datetime.to_string(parsed)
                    except ValueError:
                        continue
                return False
        return value

    def _date_from_mobile(self, value):
        if not value:
            return fields.Date.today()
        if isinstance(value, str):
            return value[:10]
        return value

    def _float_time_from_datetime(self, record, value):
        dt_value = fields.Datetime.to_datetime(value)
        local_dt = fields.Datetime.context_timestamp(record, dt_value)
        return local_dt.hour + (local_dt.minute / 60.0) + (local_dt.second / 3600.0)

    def _time_string(self, report, datetime_field, float_field):
        dt_value = report[datetime_field]
        if dt_value:
            local_dt = fields.Datetime.context_timestamp(report, dt_value)
            return f"{local_dt.hour:02d}:{local_dt.minute:02d}"
        float_value = report[float_field]
        if float_value:
            hour = int(float_value)
            minute = int(round((float_value - hour) * 60))
            return f"{hour:02d}:{minute:02d}"
        return ""
