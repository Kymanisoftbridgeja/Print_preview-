{
    "name": "Softbridge POS Browser Print",
    "summary": "Print the Odoo POS receipt directly from the browser.",
    "version": "1.0.0",
    "category": "Point of Sale",
    "author": "Softbridge",
    "license": "LGPL-3",
    "depends": ["point_of_sale"],
    "data": [
        "views/res_config_settings_views.xml",
    ],
    "assets": {
        "point_of_sale._assets_pos": [
            "softbridge_pos_browser_print/static/src/pos/browser_receipt_print.js",
            "softbridge_pos_browser_print/static/src/xml/browser_receipt_print.xml",
        ],
    },
    "installable": True,
    "application": False,
}
