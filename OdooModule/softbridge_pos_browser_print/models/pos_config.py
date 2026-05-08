from odoo import fields, models


class PosConfig(models.Model):
    _inherit = "pos.config"

    browser_receipt_print_enabled = fields.Boolean(
        string="Browser Receipt Print",
        default=False,
        help="Print the POS receipt directly from the browser print dialog.",
    )
    browser_receipt_auto_print = fields.Boolean(
        string="Auto-print Receipt in Browser",
        default=False,
        help="Automatically open the browser print flow when the receipt screen is shown.",
    )
    browser_receipt_manual_button = fields.Boolean(
        string="Show Browser Print Button",
        default=True,
        help="Show a manual Print in Browser button on the POS receipt screen.",
    )
