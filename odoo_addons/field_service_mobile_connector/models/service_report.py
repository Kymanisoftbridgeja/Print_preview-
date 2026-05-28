import secrets
from datetime import timedelta

from odoo import _, api, fields, models
from odoo.exceptions import AccessError, UserError


class FieldServiceMobileToken(models.Model):
    _name = "field.service.mobile.token"
    _description = "Field Service Mobile Access Token"
    _rec_name = "user_id"

    user_id = fields.Many2one("res.users", required=True, ondelete="cascade")
    token = fields.Char(required=True, index=True, copy=False)
    expires_at = fields.Datetime(required=True)
    active = fields.Boolean(default=True)

    @api.model
    def issue(self, user):
        token = secrets.token_urlsafe(48)
        expires_at = fields.Datetime.now() + timedelta(days=30)
        self.create({"user_id": user.id, "token": token, "expires_at": expires_at})
        return token, expires_at

    @api.model
    def authenticate(self, token):
        record = self.sudo().search(
            [
                ("token", "=", token),
                ("active", "=", True),
                ("expires_at", ">", fields.Datetime.now()),
            ],
            limit=1,
        )
        if not record:
            raise AccessError(_("Invalid or expired mobile token."))
        return record.user_id


class FieldServiceRoadReport(models.Model):
    _inherit = "field.service.road.report"

    state = fields.Selection(
        selection_add=[("assigned", "Assigned")],
        ondelete={"assigned": "set default"},
    )
    mobile_external_id = fields.Char(index=True, copy=False)
    mobile_last_sync_at = fields.Datetime(readonly=True, copy=False)

    _sql_constraints = [
        (
            "mobile_external_id_unique",
            "unique(mobile_external_id)",
            "This mobile report has already been synced.",
        )
    ]

    def _check_write_allowed(self, vals):
        if self.env.context.get("service_report_skip_lock"):
            return
        if self.env.user.has_group("field_service_road_reports.group_service_report_manager"):
            return
        allowed_submitted_write = set(vals) <= {"state"} and vals.get("state") == "submitted"
        if allowed_submitted_write:
            return
        editable_states = ("draft", "assigned", "in_progress", "completed", "rejected")
        locked = self.filtered(lambda report: report.state not in editable_states)
        if locked:
            raise UserError(
                _(
                    "Submitted reports cannot be edited by technicians. "
                    "Ask a reviewer to send it back for correction."
                )
            )


class FieldServiceRoadReportLine(models.Model):
    _inherit = "field.service.road.report.line"

    def _check_report_editable(self):
        if self.env.context.get("service_report_skip_lock"):
            return
        if self.env.user.has_group("field_service_road_reports.group_service_report_manager"):
            return
        editable_states = ("draft", "assigned", "in_progress", "completed", "rejected")
        locked = self.filtered(lambda line: line.report_id.state not in editable_states)
        if locked:
            raise AccessError(_("Submitted report lines cannot be edited by technicians."))
