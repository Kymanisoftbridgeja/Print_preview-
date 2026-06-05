from odoo import _, api, fields, models
from odoo.exceptions import UserError


class FieldServiceRoadReport(models.Model):
    _name = "field.service.road.report"
    _description = "Field Service Road Report"
    _inherit = ["mail.thread", "mail.activity.mixin"]
    _order = "service_date desc, id desc"

    name = fields.Char(
        string="Report Number",
        required=True,
        copy=False,
        default=lambda self: _("New"),
        tracking=True,
    )
    task_id = fields.Many2one(
        "project.task",
        string="Related Field Service Job",
        ondelete="set null",
        tracking=True,
        domain="[('is_fsm', '=', True)]",
    )
    field_service_job_id = fields.Many2one(
        "project.task",
        string="Field Service Job",
        related="task_id",
        store=True,
        readonly=False,
        domain="[('is_fsm', '=', True)]",
    )
    source = fields.Selection(
        [
            ("backend", "Backend"),
            ("mobile", "Mobile"),
        ],
        default="backend",
        required=True,
        tracking=True,
    )
    submitted_from_mobile = fields.Boolean(string="Completed From Mobile", copy=False, tracking=True)
    mobile_external_id = fields.Char(index=True, copy=False)
    mobile_sync_status = fields.Selection(
        [
            ("not_synced", "Not Synced"),
            ("pending_sync", "Pending Sync"),
            ("synced", "Synced"),
            ("sync_failed", "Sync Failed"),
        ],
        default="not_synced",
        copy=False,
        tracking=True,
    )
    mobile_last_sync_date = fields.Datetime(readonly=True, copy=False)
    mobile_device_id = fields.Char(copy=False)
    is_emergency_report = fields.Boolean(
        compute="_compute_mobile_report_flags",
        store=True,
        copy=False,
    )
    submitted_date = fields.Datetime(
        string="Completed Date",
        related="submitted_at",
        store=True,
        readonly=False,
    )
    submitted_by = fields.Many2one(
        "res.users",
        string="Completed By",
        readonly=True,
        copy=False,
    )
    report_origin = fields.Selection(
        [
            ("job", "Field Service Job"),
            ("emergency", "Emergency / Unscheduled Report"),
        ],
        default="emergency",
        required=True,
        tracking=True,
    )
    sync_state = fields.Selection(
        [
            ("synced", "Synced"),
            ("local_draft", "Local Draft"),
            ("pending_sync", "Pending Sync"),
            ("sync_failed", "Sync Failed"),
        ],
        default="synced",
        tracking=True,
    )
    follow_up_task_id = fields.Many2one(
        "project.task",
        string="Follow-Up Field Service Job",
        readonly=True,
        copy=False,
        domain="[('is_fsm', '=', True)]",
    )
    related_task_count = fields.Integer(compute="_compute_related_task_count")
    customer_id = fields.Many2one(
        "res.partner",
        string="Customer",
        required=True,
        tracking=True,
    )
    billable_partner_id = fields.Many2one(
        "res.partner",
        string="Billable Customer",
        tracking=True,
    )
    contact_person_id = fields.Many2one(
        "res.partner",
        string="Site Contact",
    )
    customer_address = fields.Char()
    customer_phone = fields.Char()
    customer_email = fields.Char()
    report_company_name = fields.Char(string="Company Name")
    report_address = fields.Char(string="Address")
    site_contact = fields.Char()
    external_report_number = fields.Char(string="Original Report Number")
    purchase_order = fields.Char(string="PO / Reference")
    service_type = fields.Char()
    service_date = fields.Date(default=fields.Date.context_today, required=True)
    start_datetime = fields.Datetime(string="Start Time", tracking=True)
    stop_datetime = fields.Datetime(string="Stop Time", tracking=True)
    driver_id = fields.Many2one(
        "res.users",
        string="Driver / Technician",
        default=lambda self: self.env.user,
        tracking=True,
    )
    technician_id = fields.Many2one(
        "res.users",
        string="Technician",
        related="driver_id",
        store=True,
        readonly=False,
    )
    assigned_user_id = fields.Many2one(
        "res.users",
        string="Assigned User",
        related="driver_id",
        store=True,
        readonly=False,
    )
    vehicle = fields.Char()
    location = fields.Char(string="Service Location")
    arrival_time = fields.Float(string="Arrival Time")
    departure_time = fields.Float(string="Departure Time")
    labor_hours = fields.Float(compute="_compute_labor_hours", store=True)
    odometer_start = fields.Float()
    odometer_end = fields.Float()
    mileage = fields.Float(compute="_compute_mileage", store=True)
    equipment_name = fields.Char(string="Equipment / Unit")
    equipment_make = fields.Char(string="Make")
    equipment_model = fields.Char(string="Model")
    equipment_kva = fields.Char(string="KVA")
    make_model_kva = fields.Char(string="Make | Model | KVA")
    equipment_type = fields.Char(string="Equipment Type")
    equipment_serial = fields.Char(string="Serial Number")
    equipment_type_serial = fields.Char(string="Equipment Type | Serial #")
    load = fields.Char(string="Load")
    input_output_voltage = fields.Char(string="Input | Output Voltage")
    ups_system_down = fields.Char(string="UPS System Down")
    battery_manufacturer_type = fields.Char(string="Battery Manufacturer & Type")
    battery_rating_quantity = fields.Char(string="Battery Rating | Quantity")
    issue_reported = fields.Text()
    problem_service_rendered = fields.Text(
        string="Report Problem / Service Rendered"
    )
    defects_found = fields.Text(string="Defects Found on Inspection")
    diagnosis = fields.Text()
    corrective_action_taken = fields.Text(string="Corrective Action Taken")
    resolution = fields.Text()
    work_performed = fields.Text()
    recommendations = fields.Text()
    customer_notes = fields.Text()
    internal_review_notes = fields.Text()
    technicians_on_site = fields.Char(string="Technician(s) On-Site")
    service_status = fields.Char(string="Status of Service")
    customer_name = fields.Char(string="Customer Name")
    follow_up_required = fields.Boolean(tracking=True)
    completed_on_site = fields.Boolean(default=True, tracking=True)
    create_timesheet = fields.Boolean(
        string="Create Timesheet",
        tracking=True,
    )
    create_invoice = fields.Boolean(
        string="Invoice Required",
        tracking=True,
    )
    timesheet_id = fields.Many2one(
        "account.analytic.line",
        string="Timesheet Entry",
        readonly=True,
        copy=False,
    )
    sale_order_id = fields.Many2one(
        "sale.order",
        string="Quotation / Sales Order",
        copy=False,
    )
    invoice_id = fields.Many2one(
        "account.move",
        string="Invoice Reference",
        copy=False,
        domain="[('move_type', 'in', ('out_invoice', 'out_refund'))]",
    )
    manual_quotation_number = fields.Char(string="Manual Quotation Number")
    manual_invoice_number = fields.Char(string="Manual Invoice Number")
    invoice_ids = fields.Many2many(
        "account.move",
        compute="_compute_invoice_ids",
        string="Invoices",
    )
    invoice_count = fields.Integer(compute="_compute_invoice_ids")
    line_ids = fields.One2many(
        "field.service.road.report.line",
        "report_id",
        string="Materials and Time",
    )
    planned_line_ids = fields.One2many(
        "field.service.road.report.line",
        "report_id",
        string="Planned Products / Services",
        domain=[("line_type", "=", "planned")],
        context={"default_line_type": "planned"},
    )
    actual_line_ids = fields.One2many(
        "field.service.road.report.line",
        "report_id",
        string="Actual Parts / Items Used",
        domain=[("line_type", "=", "actual")],
        context={"default_line_type": "actual"},
    )
    driver_signature = fields.Binary(attachment=True)
    customer_signature = fields.Binary(attachment=True)
    driver_signature_name = fields.Char(string="Technician Signature Name")
    customer_signature_name = fields.Char(string="Customer Signature Name")
    driver_signed_at = fields.Datetime(string="Technician Signed At")
    customer_signed_at = fields.Datetime(string="Customer Signed At")
    source_pdf = fields.Binary(string="Original PDF", attachment=True)
    source_pdf_filename = fields.Char()
    attachment_ids = fields.Many2many(
        "ir.attachment",
        "field_service_road_report_ir_attachment_rel",
        "report_id",
        "attachment_id",
        string="Photos / Attachments",
    )
    state = fields.Selection(
        [
            ("assigned", "Assigned"),
            ("in_progress", "In Progress"),
            ("completed", "Completed"),
            ("approved", "Approved"),
        ],
        default="assigned",
        required=True,
        tracking=True,
    )
    submitted_at = fields.Datetime(
        string="Completed At",
        readonly=True,
        copy=False,
        tracking=True,
    )

    @api.model_create_multi
    def create(self, vals_list):
        for vals in vals_list:
            if vals.get("name", _("New")) == _("New"):
                vals["name"] = self.env["ir.sequence"].next_by_code(
                    "field.service.road.report"
                ) or _("New")
            if vals.get("task_id"):
                vals.setdefault("report_origin", "job")
            if vals.get("field_service_job_id") and not vals.get("task_id"):
                vals["task_id"] = vals["field_service_job_id"]
            if vals.get("source") == "mobile":
                vals.setdefault("submitted_from_mobile", vals.get("state") == "completed")
                vals.setdefault("mobile_sync_status", "synced")
        reports = super().create(vals_list)
        submitted_reports = reports.filtered(lambda report: report.state == "completed")
        if submitted_reports:
            submitted_reports._sync_submission_to_task()
        return reports

    def write(self, vals):
        was_submitted = {
            report.id: report.state == "completed"
            for report in self
        }
        if vals.get("field_service_job_id") and not vals.get("task_id"):
            vals["task_id"] = vals["field_service_job_id"]
        if vals.get("state") == "completed":
            vals.setdefault("submitted_by", self.env.user.id)
            vals.setdefault("submitted_at", fields.Datetime.now())
            if vals.get("source") == "mobile" or any(report.source == "mobile" for report in self):
                vals.setdefault("submitted_from_mobile", True)
        if not self.env.context.get("service_report_skip_lock"):
            self._check_write_allowed(vals)
        result = super().write(vals)
        if vals.get("state") == "completed" or (
            vals.get("task_id") and any(report.state == "completed" for report in self)
        ):
            newly_submitted = self.filtered(
                lambda report: report.state == "completed"
                and (not was_submitted.get(report.id) or vals.get("task_id"))
            )
            newly_submitted._sync_submission_to_task()
        return result

    @api.depends("task_id")
    def _compute_related_task_count(self):
        for report in self:
            report.related_task_count = 1 if report.task_id else 0

    @api.depends("source", "task_id")
    def _compute_mobile_report_flags(self):
        for report in self:
            report.is_emergency_report = report.source == "mobile" and not report.task_id

    @api.onchange("task_id")
    def _onchange_task_id(self):
        for report in self:
            task = report.task_id
            if not task:
                continue
            report.report_origin = "job"
            if task.partner_id:
                report.customer_id = task.partner_id
                report._apply_customer_values(task.partner_id)
            if task.service_contact_id:
                report.customer_id = task.service_contact_id
                report._apply_customer_values(task.service_contact_id)
            report.purchase_order = task.service_po_reference
            report.service_type = (
                dict(task._fields["service_type"].selection).get(task.service_type, "")
                if task.service_type
                else ""
            )
            if not report.issue_reported:
                report.issue_reported = task.description or ""

    @api.onchange("customer_id")
    def _onchange_customer_id(self):
        for report in self:
            customer = report.customer_id
            if not customer:
                continue
            report._apply_customer_values(customer)

    @api.depends("sale_order_id", "invoice_id")
    def _compute_invoice_ids(self):
        AccountMove = self.env["account.move"]
        for report in self:
            invoices = report.invoice_id
            if not invoices and report.sale_order_id:
                invoices = report.sale_order_id.invoice_ids
            report.invoice_ids = invoices
            report.invoice_count = len(invoices)

    @api.depends("arrival_time", "departure_time", "start_datetime", "stop_datetime")
    def _compute_labor_hours(self):
        for report in self:
            if report.start_datetime and report.stop_datetime:
                delta = report.stop_datetime - report.start_datetime
                report.labor_hours = max(delta.total_seconds() / 3600.0, 0.0)
            elif report.departure_time >= report.arrival_time:
                report.labor_hours = report.departure_time - report.arrival_time
            else:
                report.labor_hours = 0.0

    @api.depends("odometer_start", "odometer_end")
    def _compute_mileage(self):
        for report in self:
            if report.odometer_end >= report.odometer_start:
                report.mileage = report.odometer_end - report.odometer_start
            else:
                report.mileage = 0.0

    def action_submit(self):
        for report in self:
            if report.state not in ("assigned", "in_progress", "completed"):
                raise UserError(_("Only assigned, in-progress, or completed reports can be completed."))
            signature_values = {}
            now = fields.Datetime.now()
            if report.customer_signature and not report.customer_signed_at:
                signature_values["customer_signed_at"] = now
            if report.driver_signature and not report.driver_signed_at:
                signature_values["driver_signed_at"] = now
            if signature_values:
                report.with_context(service_report_skip_lock=True).write(signature_values)
        self.with_context(service_report_skip_lock=True).write(
            {
                "state": "completed",
                "submitted_at": fields.Datetime.now(),
                "submitted_by": self.env.user.id,
                "sync_state": "synced",
                "mobile_sync_status": "synced",
            }
        )

    def action_start_service(self):
        for report in self:
            if report.state not in ("assigned", "completed"):
                raise UserError(_("Only assigned or completed reports can be started."))
            now = fields.Datetime.now()
            values = {
                "start_datetime": now,
                "service_date": fields.Date.context_today(report),
                "state": "in_progress",
                "mobile_sync_status": "synced" if report.source == "mobile" else report.mobile_sync_status,
            }
            if not report.arrival_time:
                values["arrival_time"] = report._float_time_from_datetime(now)
            report.with_context(service_report_skip_lock=True).write(values)
        return True

    def action_stop_service(self):
        for report in self:
            if report.state != "in_progress":
                raise UserError(_("Only in-progress reports can be stopped."))
            now = fields.Datetime.now()
            values = {
                "stop_datetime": now,
                "departure_time": report._float_time_from_datetime(now),
                "state": "completed",
                "mobile_sync_status": "synced" if report.source == "mobile" else report.mobile_sync_status,
            }
            report.with_context(service_report_skip_lock=True).write(values)
        return True

    def action_approve(self):
        self._check_manager()
        for report in self:
            if report.state != "completed":
                raise UserError(_("Only completed reports can be approved."))
        self.write({"state": "approved"})

    def action_reject(self):
        self.action_reset_to_assigned()

    def action_reset_to_draft(self):
        self.action_reset_to_assigned()

    def action_reset_to_assigned(self):
        self._check_manager()
        self.write({"state": "assigned"})

    def action_cancel(self):
        self._check_manager()
        raise UserError(_("Cancel is no longer part of the simplified service report workflow."))

    def action_create_timesheet(self):
        self._check_manager()
        for report in self:
            if not report.task_id:
                raise UserError(_("Select a Field Service job before creating a timesheet."))
            if not report.labor_hours:
                raise UserError(_("Enter arrival/departure time before creating a timesheet."))

            vals = {
                "name": report._timesheet_name(),
                "date": report.service_date,
                "unit_amount": report.labor_hours,
            }
            AnalyticLine = self.env["account.analytic.line"]
            if "project_id" in AnalyticLine._fields:
                vals["project_id"] = report.task_id.project_id.id
            if "task_id" in AnalyticLine._fields:
                vals["task_id"] = report.task_id.id
            if "user_id" in AnalyticLine._fields:
                vals["user_id"] = report.driver_id.id
            employee = (
                report.driver_id.employee_id
                if report.driver_id and "employee_id" in report.driver_id._fields
                else False
            )
            if employee and "employee_id" in AnalyticLine._fields:
                vals["employee_id"] = employee.id

            if report.timesheet_id:
                report.timesheet_id.write(vals)
            else:
                report.timesheet_id = AnalyticLine.create(vals)
        return True

    def action_create_sale_order(self):
        raise UserError(_("Quotations are no longer auto-created from Service Reports. Link the quotation manually on the report."))

    def action_create_invoice(self):
        raise UserError(_("Invoices are no longer auto-created from Service Reports. Link the invoice manually on the report."))

    def action_create_follow_up_task(self):
        self._check_manager()
        for report in self:
            if not report.follow_up_required:
                raise UserError(_("Mark Follow Up Required before creating a job."))
            if report.follow_up_task_id:
                continue
            if not report.customer_id:
                raise UserError(_("Select a customer before creating a follow-up job."))

            project = report._get_follow_up_project()
            values = {
                "name": report._follow_up_task_name(),
                "partner_id": report.customer_id.id,
                "description": report._follow_up_task_description(),
                "project_id": project.id,
            }
            Task = self.env["project.task"]
            if "is_fsm" in Task._fields:
                values["is_fsm"] = True
            task = Task.create(values)
            report.write({"follow_up_task_id": task.id})
        return True

    def action_open_follow_up_task(self):
        self.ensure_one()
        if not self.follow_up_task_id:
            raise UserError(_("No follow-up job has been created yet."))
        return {
            "type": "ir.actions.act_window",
            "name": _("Follow-Up Field Service Job"),
            "res_model": "project.task",
            "res_id": self.follow_up_task_id.id,
            "view_mode": "form",
        }

    def action_open_sale_order(self):
        self.ensure_one()
        if not self.sale_order_id:
            raise UserError(_("No quotation or sales order has been created yet."))
        return {
            "type": "ir.actions.act_window",
            "name": _("Quotation / Sales Order"),
            "res_model": "sale.order",
            "res_id": self.sale_order_id.id,
            "view_mode": "form",
        }

    def action_open_report_from_task(self):
        self.ensure_one()
        return {
            "type": "ir.actions.act_window",
            "name": _("Service Report"),
            "res_model": "field.service.road.report",
            "res_id": self.id,
            "view_mode": "form",
        }

    def action_open_related_task(self):
        self.ensure_one()
        if not self.task_id:
            raise UserError(_("This service report is not linked to a Field Service job."))
        return {
            "type": "ir.actions.act_window",
            "name": _("Field Service Job"),
            "res_model": "project.task",
            "res_id": self.task_id.id,
            "view_mode": "form",
        }

    def action_open_invoices(self):
        self.ensure_one()
        return {
            "type": "ir.actions.act_window",
            "name": _("Invoices"),
            "res_model": "account.move",
            "view_mode": "list,form",
            "domain": [("id", "in", self.invoice_ids.ids)],
        }

    def _timesheet_name(self):
        self.ensure_one()
        return _("%(report)s - %(work)s") % {
            "report": self.name,
            "work": (self.work_performed or self.issue_reported or _("Field service work"))[:80],
        }

    def _prepare_sale_order_values(self):
        self.ensure_one()
        values = {
            "partner_id": self._quotation_partner().id,
            "origin": self.name,
            "client_order_ref": self.purchase_order or self.external_report_number or False,
        }
        if "task_id" in self.env["sale.order"]._fields:
            values["task_id"] = self.task_id.id
        return values

    def _quotation_partner(self):
        self.ensure_one()
        return (
            self.billable_partner_id
            or getattr(self.customer_id, "commercial_partner_id", False)
            or self.customer_id
        )

    def _apply_customer_values(self, customer):
        self.ensure_one()
        company = self._customer_company(customer)
        contact = customer if company != customer else False
        self.billable_partner_id = company
        self.contact_person_id = contact
        self.report_company_name = company.name
        self.customer_name = contact.name if contact else customer.name
        self.customer_address = self._partner_value(customer, "contact_address")
        self.customer_phone = self._partner_phone(customer)
        self.customer_email = self._partner_value(customer, "email")
        self.report_address = self._partner_value(customer, "contact_address")
        self.location = self._partner_value(customer, "contact_address")

    def _customer_company(self, customer):
        parent = customer.parent_id if "parent_id" in customer._fields else False
        return parent or customer

    def _float_time_from_datetime(self, value):
        local_dt = fields.Datetime.context_timestamp(self, value)
        return local_dt.hour + (local_dt.minute / 60.0) + (local_dt.second / 3600.0)

    def _get_follow_up_project(self):
        self.ensure_one()
        Project = self.env["project.project"]
        domain = []
        if "is_fsm" in Project._fields:
            domain = [("is_fsm", "=", True)]
        project = Project.search(domain, limit=1)
        if not project:
            raise UserError(
                _("No Field Service project was found. Create one before making follow-up jobs.")
            )
        return project

    def _follow_up_task_name(self):
        self.ensure_one()
        return _("Follow-up for %(report)s - %(customer)s") % {
            "report": self.name,
            "customer": self.report_company_name or self.customer_id.display_name,
        }

    def _follow_up_task_description(self):
        self.ensure_one()
        lines = [
            _("Created from road report: %s") % self.name,
            "",
            _("Problem / Service Rendered:"),
            self.problem_service_rendered or "",
            "",
            _("Defects Found:"),
            self.defects_found or "",
            "",
            _("Corrective Action Taken:"),
            self.corrective_action_taken or "",
            "",
            _("Recommendations:"),
            self.recommendations or "",
        ]
        return "\n".join(lines)

    def _sync_sale_order_lines(self, order, invoice_lines):
        self.ensure_one()
        SaleOrderLine = self.env["sale.order.line"]
        managed_lines = order.order_line.filtered(
            lambda line: line.road_report_line_id.report_id == self
        )
        for stale_line in managed_lines.filtered(
            lambda line: line.road_report_line_id not in invoice_lines
        ):
            stale_line.unlink()
        for report_line in invoice_lines:
            sale_line = managed_lines.filtered(
                lambda line: line.road_report_line_id == report_line
            )[:1]
            values = report_line._prepare_sale_order_line_values(order)
            if sale_line:
                sale_line.write(values)
            else:
                SaleOrderLine.create(values)

    def _sync_submission_to_task(self):
        for report in self.filtered("task_id"):
            values = {}
            if not report.submitted_at:
                values["submitted_at"] = fields.Datetime.now()
            if report.sync_state != "synced":
                values["sync_state"] = "synced"
            if values:
                report.with_context(service_report_skip_lock=True).write(values)
            report.task_id.message_post(
                body=_("Service report %(report)s was completed and is ready for review.")
                % {"report": report.display_name},
                subtype_xmlid="mail.mt_note",
            )

    def _partner_value(self, partner, field_name):
        if not partner or field_name not in partner._fields:
            return ""
        return partner[field_name] or ""

    def _partner_phone(self, partner):
        phone = self._partner_value(partner, "phone")
        mobile = self._partner_value(partner, "mobile")
        return phone or mobile

    def _check_manager(self):
        if not self.env.user.has_group(
            "field_service_road_reports.group_service_report_manager"
        ):
            raise UserError(_("Only service report reviewers/managers can do this."))

    def _check_write_allowed(self, vals):
        if self.env.user.has_group(
            "field_service_road_reports.group_service_report_manager"
        ):
            return
        allowed_submitted_write = set(vals) <= {"state"} and vals.get("state") == "completed"
        if allowed_submitted_write and not any(report.state == "approved" for report in self):
            return
        editable_states = ("assigned", "in_progress", "completed")
        locked = self.filtered(lambda report: report.state not in editable_states)
        if locked:
            raise UserError(
                _("Approved reports cannot be edited by technicians. Ask a reviewer to reset it to Assigned if correction is needed.")
            )


class FieldServiceRoadReportLine(models.Model):
    _name = "field.service.road.report.line"
    _description = "Field Service Road Report Line"
    _order = "sequence, id"

    sequence = fields.Integer(default=10)
    line_type = fields.Selection(
        [
            ("planned", "Planned"),
            ("actual", "Actual"),
        ],
        default="actual",
        required=True,
    )
    report_id = fields.Many2one(
        "field.service.road.report",
        required=True,
        ondelete="cascade",
    )
    product_id = fields.Many2one("product.product", string="Product / Service")
    name = fields.Char(string="Description", required=True)
    serial_number = fields.Char(string="Serial #")
    quantity = fields.Float(default=1.0)
    uom_id = fields.Many2one("uom.uom", string="Unit")
    price_unit = fields.Float(string="Unit Price")
    invoiceable = fields.Boolean(default=True)
    notes = fields.Char()

    @api.onchange("product_id")
    def _onchange_product_id(self):
        for line in self:
            product = line.product_id
            if not product:
                continue
            line.name = product.display_name
            line.uom_id = product.uom_id
            line.price_unit = product.lst_price

    def _prepare_sale_order_line_values(self, order):
        self.ensure_one()
        values = {
            "order_id": order.id,
            "name": self.name,
            "product_uom_qty": self.quantity,
            "price_unit": self.price_unit,
        }
        if self.product_id:
            values["product_id"] = self.product_id.id
        if self.uom_id:
            values["product_uom"] = self.uom_id.id
        if "road_report_line_id" in self.env["sale.order.line"]._fields:
            values["road_report_line_id"] = self.id
        return values

    @api.model_create_multi
    def create(self, vals_list):
        records = super().create(vals_list)
        records._check_report_editable()
        return records

    def write(self, vals):
        self._check_report_editable()
        return super().write(vals)

    def unlink(self):
        self._check_report_editable()
        return super().unlink()

    def _check_report_editable(self):
        if self.env.context.get("service_report_skip_lock"):
            return
        if self.env.user.has_group(
            "field_service_road_reports.group_service_report_manager"
        ):
            return
        editable_states = ("assigned", "in_progress", "completed")
        locked = self.filtered(lambda line: line.report_id.state not in editable_states)
        if locked:
            raise UserError(
                _("Approved report lines cannot be edited by technicians.")
            )


class SaleOrderLine(models.Model):
    _inherit = "sale.order.line"

    road_report_line_id = fields.Many2one(
        "field.service.road.report.line",
        string="Road Report Line",
        copy=False,
        ondelete="set null",
    )
