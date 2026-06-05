from odoo import _, api, fields, models
from odoo.exceptions import UserError


class ProjectTask(models.Model):
    _inherit = "project.task"

    service_contact_id = fields.Many2one(
        "res.partner",
        string="Contact Person",
        domain="[('parent_id', '=', partner_id)]",
    )
    service_address = fields.Char(string="Service Address")
    service_phone = fields.Char(string="Phone")
    service_email = fields.Char(string="Email")
    service_po_reference = fields.Char(string="PO / Reference")
    service_type = fields.Selection(
        [
            ("preventive_maintenance", "Preventive Maintenance"),
            ("routine_service", "Routine Service"),
            ("battery_change", "Battery Change"),
            ("ups_inspection", "UPS Inspection"),
            ("emergency_service", "Emergency Service"),
            ("installation", "Installation"),
            ("repair", "Repair"),
        ],
        string="Service Type",
    )
    planned_item_line_ids = fields.One2many(
        "field.service.job.item.line",
        "task_id",
        string="Products / Services / Items to Bring",
    )

    road_report_ids = fields.One2many(
        "field.service.road.report",
        "task_id",
        string="Road Reports",
    )
    road_report_count = fields.Integer(compute="_compute_road_report_count")
    submitted_road_report_count = fields.Integer(
        compute="_compute_road_report_count"
    )
    road_report_quotation_count = fields.Integer(
        compute="_compute_road_report_quotation_count"
    )
    latest_road_report_id = fields.Many2one(
        "field.service.road.report",
        compute="_compute_latest_road_report",
    )
    latest_road_report_state = fields.Selection(
        related="latest_road_report_id.state",
    )
    latest_road_report_sync_state = fields.Selection(
        related="latest_road_report_id.sync_state",
    )
    latest_road_report_submitted_at = fields.Datetime(
        related="latest_road_report_id.submitted_at",
    )

    def _compute_road_report_count(self):
        grouped = self.env["field.service.road.report"].read_group(
            [("task_id", "in", self.ids)],
            ["task_id"],
            ["task_id"],
        )
        counts = {
            result["task_id"][0]: result["task_id_count"]
            for result in grouped
            if result.get("task_id")
        }
        submitted_grouped = self.env["field.service.road.report"].read_group(
            [("task_id", "in", self.ids), ("state", "=", "completed")],
            ["task_id"],
            ["task_id"],
        )
        submitted_counts = {
            result["task_id"][0]: result["task_id_count"]
            for result in submitted_grouped
            if result.get("task_id")
        }
        for task in self:
            task.road_report_count = counts.get(task.id, 0)
            task.submitted_road_report_count = submitted_counts.get(task.id, 0)

    def _compute_road_report_quotation_count(self):
        for task in self:
            task.road_report_quotation_count = len(
                task.road_report_ids.mapped("sale_order_id")
            )

    def _compute_latest_road_report(self):
        Report = self.env["field.service.road.report"]
        for task in self:
            task.latest_road_report_id = Report.search(
                [("task_id", "=", task.id)],
                order="service_date desc, id desc",
                limit=1,
            )

    def action_start_road_report(self):
        self.ensure_one()
        self._check_can_start_road_report()
        report = self._ensure_road_report()
        return report.action_open_report_from_task()

    def action_open_road_reports(self):
        self.ensure_one()
        action = self.env.ref(
            "field_service_road_reports.action_field_service_road_report"
        ).read()[0]
        action["domain"] = [("task_id", "=", self.id)]
        action["context"] = {
            "default_task_id": self.id,
            "default_customer_id": self.partner_id.id,
            "default_report_origin": "job",
        }
        return action

    def action_open_submitted_road_reports(self):
        self.ensure_one()
        action = self.action_open_road_reports()
        action["name"] = _("Completed Service Reports")
        action["domain"] = [("task_id", "=", self.id), ("state", "=", "completed")]
        return action

    def action_open_road_report_quotations(self):
        self.ensure_one()
        quotation_ids = self.road_report_ids.mapped("sale_order_id").ids
        return {
            "type": "ir.actions.act_window",
            "name": _("Service Report Quotations"),
            "res_model": "sale.order",
            "view_mode": "list,form",
            "domain": [("id", "in", quotation_ids)],
        }

    def action_approve_road_report(self):
        self.ensure_one()
        report = self._get_latest_road_report()
        report.action_approve()
        return report.action_open_report_from_task()

    def _get_latest_road_report(self):
        self.ensure_one()
        report = self.latest_road_report_id
        if not report:
            raise UserError(_("No service report has been created for this job."))
        return report

    @api.model_create_multi
    def create(self, vals_list):
        tasks = super().create(vals_list)
        tasks._ensure_road_reports_after_job_change()
        return tasks

    def write(self, vals):
        result = super().write(vals)
        if set(vals) & {
            "partner_id",
            "service_contact_id",
            "service_address",
            "service_phone",
            "service_email",
            "service_po_reference",
            "service_type",
            "user_ids",
            "user_id",
            "planned_item_line_ids",
            "description",
        }:
            self._ensure_road_reports_after_job_change()
            self._sync_open_reports_from_job()
        return result

    @api.onchange("partner_id")
    def _onchange_partner_id_service_report(self):
        for task in self:
            partner = task.partner_id
            if not partner:
                continue
            company = partner.parent_id if "parent_id" in partner._fields and partner.parent_id else partner
            task.service_contact_id = partner if partner != company else False
            task.service_address = task._partner_value(partner, "contact_address")
            task.service_phone = task._partner_phone(partner)
            task.service_email = task._partner_value(partner, "email")

    @api.onchange("service_contact_id")
    def _onchange_service_contact_id(self):
        for task in self:
            contact = task.service_contact_id
            if not contact:
                continue
            task.service_address = task._partner_value(contact, "contact_address")
            task.service_phone = task._partner_phone(contact)
            task.service_email = task._partner_value(contact, "email")

    def _check_can_start_road_report(self):
        if self.env.user.has_group(
            "field_service_road_reports.group_service_report_manager"
        ):
            return
        assigned_users = self.user_ids if "user_ids" in self._fields else self.env["res.users"]
        if self.env.user not in assigned_users:
            raise UserError(_("You can only start reports for jobs assigned to you."))

    def _prepare_road_report_values(self):
        self.ensure_one()
        customer = self.service_contact_id or self.partner_id
        company = self._customer_company(customer)
        driver = self._assigned_report_user()
        values = {
            "task_id": self.id,
            "field_service_job_id": self.id,
            "report_origin": "job",
            "customer_id": customer.id,
            "billable_partner_id": company.id,
            "contact_person_id": customer.id if customer != company else False,
            "driver_id": driver.id if driver else self.env.user.id,
            "state": "assigned",
            "report_company_name": company.name,
            "customer_name": customer.name,
            "site_contact": customer.name if customer != company else "",
            "report_address": self.service_address or self._partner_value(customer, "contact_address"),
            "customer_address": self.service_address or self._partner_value(customer, "contact_address"),
            "customer_phone": self.service_phone or self._partner_phone(customer),
            "customer_email": self.service_email or self._partner_value(customer, "email"),
            "location": self.service_address or self._partner_value(customer, "contact_address"),
            "purchase_order": self.service_po_reference,
            "service_type": dict(self._fields["service_type"].selection).get(self.service_type, "") if self.service_type else "",
            "problem_service_rendered": self.description or "",
            "planned_line_ids": [
                (0, 0, line._prepare_report_line_values("planned"))
                for line in self.planned_item_line_ids
            ],
        }
        if "product_id" in self._fields and self.product_id:
            values["equipment_name"] = self.product_id.display_name
        return values

    def _ensure_road_reports_after_job_change(self):
        for task in self:
            if task._is_field_service_job() and task.partner_id and not task.road_report_ids:
                task._ensure_road_report()

    def _ensure_road_report(self):
        self.ensure_one()
        if not self.partner_id:
            raise UserError(_("Add a customer to the Field Service job first."))
        report = self.env["field.service.road.report"].search(
            [("task_id", "=", self.id)],
            order="service_date desc, id desc",
            limit=1,
        )
        if report:
            return report
        return self.env["field.service.road.report"].with_context(
            service_report_skip_lock=True
        ).create(self._prepare_road_report_values())

    def _sync_open_reports_from_job(self):
        for task in self:
            reports = task.road_report_ids.filtered(
                lambda report: report.state in ("assigned", "in_progress")
            )
            for report in reports:
                values = task._prepare_road_report_values()
                values.pop("planned_line_ids", None)
                values.pop("state", None)
                report.with_context(service_report_skip_lock=True).write(values)
                report.planned_line_ids.with_context(service_report_skip_lock=True).unlink()
                for line in task.planned_item_line_ids:
                    self.env["field.service.road.report.line"].with_context(
                        service_report_skip_lock=True
                    ).create(line._prepare_report_line_values("planned", report))

    def _is_field_service_job(self):
        self.ensure_one()
        return bool(self.is_fsm) if "is_fsm" in self._fields else True

    def _assigned_report_user(self):
        self.ensure_one()
        if "user_ids" in self._fields and self.user_ids:
            return self.user_ids[:1]
        if "user_id" in self._fields and self.user_id:
            return self.user_id
        return self.env.user

    def _customer_company(self, customer):
        parent = customer.parent_id if customer and "parent_id" in customer._fields else False
        return parent or customer

    def _partner_value(self, partner, field_name):
        if not partner or field_name not in partner._fields:
            return ""
        return partner[field_name] or ""

    def _partner_phone(self, partner):
        return self._partner_value(partner, "phone") or self._partner_value(partner, "mobile")


class FieldServiceJobItemLine(models.Model):
    _name = "field.service.job.item.line"
    _description = "Field Service Job Planned Product / Service / Item"
    _order = "sequence, id"

    sequence = fields.Integer(default=10)
    task_id = fields.Many2one("project.task", required=True, ondelete="cascade")
    product_id = fields.Many2one("product.product", string="Product / Service")
    name = fields.Char(string="Description", required=True)
    quantity = fields.Float(default=1.0)
    notes = fields.Char()
    invoiceable = fields.Boolean(string="Invoiceable", default=False)

    @api.onchange("product_id")
    def _onchange_product_id(self):
        for line in self:
            if line.product_id:
                line.name = line.product_id.display_name

    def _prepare_report_line_values(self, line_type="planned", report=False):
        self.ensure_one()
        values = {
            "sequence": self.sequence,
            "line_type": line_type,
            "product_id": self.product_id.id,
            "name": self.name,
            "quantity": self.quantity,
            "invoiceable": self.invoiceable,
            "notes": self.notes,
        }
        if report:
            values["report_id"] = report.id
        return values
