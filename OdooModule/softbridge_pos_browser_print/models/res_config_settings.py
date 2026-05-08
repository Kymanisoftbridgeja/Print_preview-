from odoo import fields, models


class ResConfigSettings(models.TransientModel):
    _inherit = "res.config.settings"

    browser_receipt_print_enabled = fields.Boolean(
        related="pos_config_id.browser_receipt_print_enabled",
        readonly=False,
    )
    browser_receipt_auto_print = fields.Boolean(
        related="pos_config_id.browser_receipt_auto_print",
        readonly=False,
    )
    browser_receipt_manual_button = fields.Boolean(
        related="pos_config_id.browser_receipt_manual_button",
        readonly=False,
    )
    pos_browser_receipt_print_enabled = fields.Boolean(
        related="pos_config_id.browser_receipt_print_enabled",
        readonly=False,
    )
    pos_browser_receipt_auto_print = fields.Boolean(
        related="pos_config_id.browser_receipt_auto_print",
        readonly=False,
    )
    pos_browser_receipt_manual_button = fields.Boolean(
        related="pos_config_id.browser_receipt_manual_button",
        readonly=False,
    )
