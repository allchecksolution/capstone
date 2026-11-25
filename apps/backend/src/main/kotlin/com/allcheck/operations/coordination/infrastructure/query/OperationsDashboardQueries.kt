package com.allcheck.operations.coordination.infrastructure.query

import com.allcheck.operations.DashboardFilter
import com.allcheck.operations.DashboardOperations
import com.allcheck.operations.DashboardRequest
import com.allcheck.operations.DashboardStatisticsGroup
import com.allcheck.operations.FollowUpTarget
import com.allcheck.operations.RequestIntake
import com.allcheck.operations.RequestStatus
import com.allcheck.operations.RoundStatus
import com.allcheck.operations.WorkStatus
import com.allcheck.operations.WorkType
import com.allcheck.operations.coordination.infrastructure.dispatch.AdministrativeArea
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Repository
internal class OperationsDashboardQueries(private val jdbc: JdbcClient, private val mapper: JsonMapper) : DashboardOperations {

    override fun requestPage(filter: DashboardFilter, offset: Int, limit: Int): List<DashboardRequest> {
        val (where, params) = conditions(filter)
        return jdbc.sql(
            """
            SELECT r.request_id, r.reception_id, r.status, r.version, r.intake, r.created_at,
                   r.current_round_id, r.current_work_id, r.follow_up_target, r.follow_up_reason,
                   r.site->>'area' AS area, d.status AS round_status,
                   w.status AS work_status, w.driver_id AS work_driver_id
            FROM service_requests r
            LEFT JOIN dispatch_rounds d ON d.round_id = r.current_round_id
            LEFT JOIN work_orders w ON w.work_id = r.current_work_id
            WHERE $where
            ORDER BY r.created_at DESC, r.request_id DESC
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
        ).params(params).param("limit", limit).param("offset", offset).query { row, _ -> request(row) }.list()
    }

    override fun statistics(filter: DashboardFilter, weekAnchor: LocalDate): List<DashboardStatisticsGroup> {
        val (where, params) = conditions(filter)
        return jdbc.sql(
            """
            SELECT CAST(:anchor AS date) + ((CAST(timezone('Asia/Seoul', r.created_at) AS date) - CAST(:anchor AS date)) / 7 * 7) AS week_start,
                   r.intake->>'workType' AS work_type, r.status, count(*) AS request_count,
                   count(report.report_id) AS report_count,
                   coalesce(sum(CAST(report.latest->>'totalCost' AS numeric)), 0) AS total_cost
            FROM service_requests r
            LEFT JOIN work_reports report ON r.status = 'COMPLETED' AND report.work_id = r.current_work_id
            WHERE $where
            GROUP BY 1, 2, 3
            """.trimIndent(),
        ).params(params).param("anchor", weekAnchor).query { row, _ ->
            DashboardStatisticsGroup(
                row.getObject("week_start", LocalDate::class.java),
                WorkType.valueOf(row.getString("work_type")),
                RequestStatus.valueOf(row.getString("status")),
                row.getLong("request_count"),
                row.getLong("report_count"),
                row.getBigDecimal("total_cost"),
            )
        }.list()
    }

    private fun conditions(filter: DashboardFilter): Pair<String, Map<String, Any>> {
        val conditions = mutableListOf("true")
        val params = mutableMapOf<String, Any>()
        fun add(name: String, value: Any?, sql: String) {
            if (value == null) return
            conditions.add(sql)
            params[name] = value
        }

        add("from", filter.from?.atOffset(ZoneOffset.UTC), "r.created_at >= :from")
        add("until", filter.until?.atOffset(ZoneOffset.UTC), "r.created_at < :until")
        add("requestStatus", filter.requestStatus?.name, "r.status = :requestStatus")
        add("workType", filter.workType?.name, "r.intake->>'workType' = :workType")
        add("needsAdminAction", filter.needsAdminAction, "(r.status = 'FOLLOW_UP_REQUIRED') = :needsAdminAction")
        add(
            "query",
            filter.query,
            """(strpos(lower('req_' || r.request_id), lower(:query)) > 0
                 OR strpos(lower(r.intake->>'address'), lower(:query)) > 0
                 OR strpos(lower(r.intake->>'symptoms'), lower(:query)) > 0)""",
        )

        filter.region?.let { region ->
            val (names, remainder) = AdministrativeArea.regionPrefix(region)
            val area = "btrim(regexp_replace(r.site->>'area', '[[:space:]]+', ' ', 'g'))"
            val first = "split_part($area, ' ', 1)"
            add("areaNames", names, "$first IN (:areaNames)")
            if (remainder.isNotEmpty()) {
                add("areaTail", remainder.joinToString(" ", prefix = " ", postfix = " "), "starts_with(substring($area from length($first) + 1) || ' ', :areaTail)")
            }
        }

        if (filter.driverId != null || filter.workStatus != null) {
            val workConditions = mutableListOf("matching.request_id = r.request_id")
            filter.driverId?.let {
                workConditions.add("matching.driver_id = :driverId")
                params["driverId"] = it
            }
            filter.workStatus?.let {
                workConditions.add("matching.status = :workStatus")
                params["workStatus"] = it.name
            }
            conditions.add("EXISTS (SELECT 1 FROM work_orders matching WHERE ${workConditions.joinToString(" AND ")})")
        }
        return conditions.joinToString(" AND ") to params
    }

    private fun request(row: ResultSet): DashboardRequest = DashboardRequest(
        requestId = row.getLong("request_id"),
        receptionId = row.getLong("reception_id"),
        status = RequestStatus.valueOf(row.getString("status")),
        version = row.getInt("version"),
        intake = mapper.readValue(row.getString("intake"), RequestIntake::class.java),
        area = row.getString("area"),
        currentRoundId = row.getObject("current_round_id", Long::class.javaObjectType),
        currentRoundStatus = row.getString("round_status")?.let(RoundStatus::valueOf),
        currentWorkId = row.getObject("current_work_id", Long::class.javaObjectType),
        currentWorkStatus = row.getString("work_status")?.let(WorkStatus::valueOf),
        currentWorkDriverId = row.getObject("work_driver_id", Long::class.javaObjectType),
        followUpTarget = row.getString("follow_up_target")?.let { mapper.readValue(it, FollowUpTarget::class.java) },
        followUpReason = row.getString("follow_up_reason"),
        createdAt = row.getObject("created_at", OffsetDateTime::class.java).toInstant(),
    )
}
