from odoo import fields, models


class ResConfigSettings(models.TransientModel):
    _inherit = "res.config.settings"

    pos_softbridge_enabled = fields.Boolean(
        related="pos_config_id.softbridge_enabled",
        readonly=False,
    )
    pos_softbridge_bridge_url = fields.Char(
        related="pos_config_id.softbridge_bridge_url",
        readonly=False,
    )
    pos_softbridge_restaurant_bill_action_enabled = fields.Boolean(
        related="pos_config_id.softbridge_restaurant_bill_action_enabled",
        readonly=False,
    )
    pos_softbridge_api_token = fields.Char(
        related="pos_config_id.softbridge_api_token",
        readonly=False,
    )
    pos_softbridge_auto_send_receipt = fields.Boolean(
        related="pos_config_id.softbridge_auto_send_receipt",
        readonly=False,
    )
    pos_softbridge_manual_button = fields.Boolean(
        related="pos_config_id.softbridge_manual_button",
        readonly=False,
    )
    pos_softbridge_payload_mode = fields.Selection(
        related="pos_config_id.softbridge_payload_mode",
        readonly=False,
    )
    pos_softbridge_request_timeout_ms = fields.Integer(
        related="pos_config_id.softbridge_request_timeout_ms",
        readonly=False,
    )
