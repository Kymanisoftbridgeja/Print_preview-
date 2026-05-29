from odoo import _, fields, models
from odoo.exceptions import UserError


class ProjectTask(models.Model):
    _inherit = "project.task"

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
            [("task_id", "in", self.ids), ("state", "=", "submitted")],
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
        report = self.env["field.service.road.report"].search(
            [
                ("task_id", "=", self.id),
                ("driver_id", "=", self.env.user.id),
                ("state", "in", ["draft", "rejected"]),
            ],
            limit=1,
        )
        if not report:
            if not self.partner_id:
                raise UserError(_("Add a customer to the Field Service job first."))
            report = self.env["field.service.road.report"].create(
                self._prepare_road_report_values()
            )
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
        action["name"] = _("Submitted Service Reports")
        action["domain"] = [("task_id", "=", self.id), ("state", "=", "submitted")]
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

    def action_reject_road_report(self):
        self.ensure_one()
        report = self._get_latest_road_report()
        report.action_reject()
        return report.action_open_report_from_task()

    def action_create_road_report_quotation(self):
        self.ensure_one()
        report = self._get_latest_road_report()
        report.action_create_sale_order()
        return report.action_open_sale_order()

    def _get_latest_road_report(self):
        self.ensure_one()
        report = self.latest_road_report_id
        if not report:
            raise UserError(_("No service report has been submitted for this job."))
        return report


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
        customer = self.partner_id
        values = {
            "task_id": self.id,
            "report_origin": "job",
            "customer_id": customer.id,
            "driver_id": self.env.user.id,
            "report_company_name": customer.name,
            "report_address": customer.contact_address
            if "contact_address" in customer._fields
            else "",
            "customer_address": customer.contact_address
            if "contact_address" in customer._fields
            else "",
            "customer_phone": customer.phone if "phone" in customer._fields else "",
            "customer_email": customer.email if "email" in customer._fields else "",
            "location": customer.contact_address
            if "contact_address" in customer._fields
            else "",
            "problem_service_rendered": self.description or "",
        }
        if "product_id" in self._fields and self.product_id:
            values["equipment_name"] = self.product_id.display_name
        return values
