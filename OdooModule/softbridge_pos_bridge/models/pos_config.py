from odoo import api, fields, models
from odoo.exceptions import ValidationError


DEFAULT_SOFTBRIDGE_URL = "http://127.0.0.1:9900"
DEFAULT_SOFTBRIDGE_TIMEOUT_MS = 5000
MIN_SOFTBRIDGE_TIMEOUT_MS = 500
MAX_SOFTBRIDGE_TIMEOUT_MS = 60000


class PosConfig(models.Model):
    _inherit = "pos.config"

    softbridge_enabled = fields.Boolean(
        string="Softbridge Windows Bridge",
        default=False,
        help="Send the rendered POS receipt screen to the Softbridge Windows desktop app.",
    )
    softbridge_bridge_url = fields.Char(
        string="Softbridge Bridge URL",
        default=DEFAULT_SOFTBRIDGE_URL,
        help="Base URL of the Windows bridge. The Odoo addon appends /odoo/receipt automatically.",
    )
    softbridge_api_token = fields.Char(
        string="Softbridge Bridge Token",
        help="Optional token that the Odoo POS sends to the Windows bridge.",
    )
    softbridge_auto_send_receipt = fields.Boolean(
        string="Auto-send Receipt Screen",
        default=True,
        help="Automatically send the receipt once the Odoo receipt screen is displayed.",
    )
    softbridge_manual_button = fields.Boolean(
        string="Show Softbridge Button",
        default=True,
        help="Show a manual Send to Softbridge button on the POS receipt screen.",
    )
    softbridge_payload_mode = fields.Selection(
        selection=[
            ("text", "Receipt Text"),
            ("html", "Receipt HTML"),
            ("image", "Rendered Receipt Image"),
        ],
        string="Softbridge Payload Mode",
        default="text",
        required=True,
        help="Use receipt text for the safest output, receipt HTML for markup extraction, or rendered receipt image for the closest visual match.",
    )
    softbridge_request_timeout_ms = fields.Integer(
        string="Softbridge Request Timeout (ms)",
        default=DEFAULT_SOFTBRIDGE_TIMEOUT_MS,
        help="How long the POS waits for the Windows bridge before treating the send as failed.",
    )

    @api.constrains("softbridge_bridge_url")
    def _check_softbridge_bridge_url(self):
        for config in self:
            url = (config.softbridge_bridge_url or "").strip()
            if not url:
                raise ValidationError("The Softbridge bridge URL cannot be empty.")
            if not (url.startswith("http://") or url.startswith("https://")):
                raise ValidationError(
                    "The Softbridge bridge URL must start with http:// or https://."
                )

    @api.constrains("softbridge_request_timeout_ms")
    def _check_softbridge_request_timeout_ms(self):
        for config in self:
            timeout_ms = config.softbridge_request_timeout_ms or 0
            if timeout_ms < MIN_SOFTBRIDGE_TIMEOUT_MS or timeout_ms > MAX_SOFTBRIDGE_TIMEOUT_MS:
                raise ValidationError(
                    "The Softbridge request timeout must be between %s ms and %s ms."
                    % (MIN_SOFTBRIDGE_TIMEOUT_MS, MAX_SOFTBRIDGE_TIMEOUT_MS)
                )
