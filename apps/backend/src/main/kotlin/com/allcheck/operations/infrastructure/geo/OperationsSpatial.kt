package com.allcheck.operations.infrastructure.geo

import com.allcheck.operations.DriverAvailability
import com.allcheck.operations.SiteCoordinates
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/** 다른 도메인의 테이블에 접근하지 않고 공개 스냅샷의 WGS84 위치를 공간 연산한다. */
@Component
class OperationsSpatial(private val jdbc: JdbcTemplate) {
    private val mapper = JsonMapper.builder().build()
    fun within(site: SiteCoordinates, radiusKm: Int, drivers: List<DriverAvailability>): Map<Long, Double> {
        if (drivers.isEmpty()) return emptyMap()
        val input = mapper.writeValueAsString(drivers.map { mapOf("id" to it.driverId, "lon" to it.longitude, "lat" to it.latitude) })
        return jdbc.query(
            """
            select d.id, public.ST_Distance(p.site,p.driver) as distance
            from jsonb_to_recordset(?::jsonb) as d(id bigint,lon float8,lat float8)
            cross join lateral (select
                public.ST_SetSRID(public.ST_MakePoint(?,?),4326)::public.geography as site,
                public.ST_SetSRID(public.ST_MakePoint(d.lon,d.lat),4326)::public.geography as driver) p
            where public.ST_DWithin(p.site,p.driver,?)
            """.trimIndent(),
            { row, _ -> row.getLong("id") to row.getDouble("distance") },
            input,
            site.longitude,
            site.latitude,
            radiusKm * 1000.0,
        ).toMap()
    }
    fun fromUtmK(x: Double, y: Double, area: String): SiteCoordinates {
        require(x.isFinite() && y.isFinite())
        return jdbc.queryForObject(
            "select public.ST_X(p) as lon,public.ST_Y(p) as lat from (select public.ST_Transform(public.ST_SetSRID(public.ST_MakePoint(?,?),5179),4326) as p) t",
            { row, _ -> SiteCoordinates(row.getDouble("lon"), row.getDouble("lat"), area) },
            x,
            y,
        ) ?: error("Coordinate transformation returned no result")
    }
}
