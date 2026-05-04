{
    "name": "Softbridge POS Bridge",
    "summary": "Send Odoo POS receipt-screen output to the Softbridge Windows desktop bridge.",
    "version": "1.0.9",
    "category": "Point of Sale",
    "author": "Softbridge",
    "license": "LGPL-3",
    "depends": ["point_of_sale"],
    "data": [
        "views/res_config_settings_views.xml",
    ],
    "assets": {
        "point_of_sale._assets_pos": [
            "softbridge_pos_bridge/static/src/pos/receipt_bridge.js",
            "softbridge_pos_bridge/static/src/xml/receipt_bridge.xml",
        ],
    },
    "installable": True,
    "application": False,
}
