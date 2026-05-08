{
    "name": "Softbridge POS Restaurant Bridge",
    "summary": "Send Odoo Restaurant Action-button bills to the Softbridge Windows bridge.",
    "version": "1.0.0",
    "category": "Point of Sale",
    "author": "Softbridge",
    "license": "LGPL-3",
    "depends": ["softbridge_pos_bridge", "pos_restaurant"],
    "data": [
        "views/res_config_settings_views.xml",
    ],
    "assets": {},
    "installable": True,
    "application": False,
}
