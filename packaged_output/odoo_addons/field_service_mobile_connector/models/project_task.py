from odoo import fields, models


class ProjectTask(models.Model):
    _inherit = "project.task"

    latest_road_report_id = fields.Many2one(
        "field.service.road.report",
        compute="_compute_latest_road_report",
        search="_search_latest_road_report_id",
    )

    def _search_latest_road_report_id(self, operator, value):
        Report = self.env["field.service.road.report"]
        positive = operator in ("=", "in")
        negative = operator in ("!=", "not in")
        if not positive and not negative:
            return [("id", "=", 0)]

        if operator in ("=", "!="):
            report_ids = [value] if value else []
        else:
            report_ids = value or []

        if not report_ids:
            report_task_ids = Report.search([("task_id", "!=", False)]).mapped("task_id").ids
            return [("id", "not in" if positive else "in", report_task_ids)]

        reports = Report.browse(report_ids).exists()
        latest_task_ids = []
        for task in reports.mapped("task_id"):
            latest = Report.search(
                [("task_id", "=", task.id)],
                order="service_date desc, id desc",
                limit=1,
            )
            if latest and latest in reports:
                latest_task_ids.append(task.id)

        return [("id", "in" if positive else "not in", latest_task_ids)]
