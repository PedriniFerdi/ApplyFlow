package com.applyflow.repository;

import java.sql.Date;
import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.applyflow.dto.analytics.AnalyticsPeriod;

@Repository
public class AnalyticsRepository {

    private static final String APPLICATION_FLAGS_CTE = """
            WITH application_flags AS (
                SELECT
                    application.id,
                    application.source_id,
                    application.applied_date,
                    MAX(CASE WHEN history.status IN (:responseStatuses) THEN 1 ELSE 0 END) AS response_reached,
                    MAX(CASE WHEN history.status IN (:interviewStatuses) THEN 1 ELSE 0 END) AS interview_reached,
                    MAX(CASE WHEN history.status IN (:offerStatuses) THEN 1 ELSE 0 END) AS offer_reached,
                    MAX(CASE WHEN history.status IN (:rejectionStatuses) THEN 1 ELSE 0 END) AS rejection_reached
                FROM job_applications application
                LEFT JOIN application_status_history history
                    ON history.application_id = application.id
                WHERE application.owner_id = :ownerId
                GROUP BY application.id, application.source_id, application.applied_date
            )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AnalyticsRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SummaryCounts findSummary(
            Long ownerId,
            Collection<String> responseStatuses,
            Collection<String> interviewStatuses,
            Collection<String> offerStatuses,
            Collection<String> rejectionStatuses
    ) {
        String sql = APPLICATION_FLAGS_CTE + """
                SELECT
                    COUNT(*) AS total_applications,
                    COALESCE(SUM(CAST(CASE WHEN applied_date IS NOT NULL THEN 1 ELSE 0 END AS BIGINT)), 0)
                        AS applied_applications,
                    COALESCE(SUM(CAST(CASE WHEN applied_date IS NOT NULL THEN response_reached ELSE 0 END AS BIGINT)), 0)
                        AS response_count,
                    COALESCE(SUM(CAST(CASE WHEN applied_date IS NOT NULL THEN interview_reached ELSE 0 END AS BIGINT)), 0)
                        AS interview_count,
                    COALESCE(SUM(CAST(CASE WHEN applied_date IS NOT NULL THEN offer_reached ELSE 0 END AS BIGINT)), 0)
                        AS offer_count,
                    COALESCE(SUM(CAST(CASE WHEN applied_date IS NOT NULL THEN rejection_reached ELSE 0 END AS BIGINT)), 0)
                        AS rejection_count
                FROM application_flags
                """;
        return jdbcTemplate.queryForObject(sql, progressionParameters(ownerId,
                responseStatuses, interviewStatuses, offerStatuses, rejectionStatuses),
                (resultSet, rowNumber) -> new SummaryCounts(
                        resultSet.getLong("total_applications"),
                        resultSet.getLong("applied_applications"),
                        resultSet.getLong("response_count"),
                        resultSet.getLong("interview_count"),
                        resultSet.getLong("offer_count"),
                        resultSet.getLong("rejection_count")));
    }

    public List<FunnelCount> findFunnel(Long ownerId, Collection<String> statuses) {
        String sql = """
                SELECT history.status, COUNT(DISTINCT history.application_id) AS application_count
                FROM application_status_history history
                JOIN job_applications application ON application.id = history.application_id
                WHERE application.owner_id = :ownerId
                  AND history.status IN (:statuses)
                GROUP BY history.status
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource()
                        .addValue("ownerId", ownerId)
                        .addValue("statuses", statuses),
                (resultSet, rowNumber) -> new FunnelCount(
                        resultSet.getString("status"), resultSet.getLong("application_count")));
    }

    public List<TimeBucket> findApplicationsOverTime(Long ownerId, AnalyticsPeriod period) {
        String bucketExpression = switch (period) {
            case WEEK -> "CAST(DATE_TRUNC('week', application.applied_date) AS date)";
            case MONTH -> "CAST(DATE_TRUNC('month', application.applied_date) AS date)";
        };
        String sql = """
                SELECT %s AS bucket_start, COUNT(*) AS application_count
                FROM job_applications application
                WHERE application.owner_id = :ownerId
                  AND application.applied_date IS NOT NULL
                GROUP BY %s
                ORDER BY bucket_start ASC
                """.formatted(bucketExpression, bucketExpression);
        return jdbcTemplate.query(sql, new MapSqlParameterSource("ownerId", ownerId),
                (resultSet, rowNumber) -> {
                    Date bucketStart = resultSet.getDate("bucket_start");
                    return new TimeBucket(bucketStart.toLocalDate(), resultSet.getLong("application_count"));
                });
    }

    public List<DimensionCounts> findSources(
            Long ownerId,
            Collection<String> responseStatuses,
            Collection<String> interviewStatuses,
            Collection<String> offerStatuses,
            Collection<String> rejectionStatuses
    ) {
        String sql = APPLICATION_FLAGS_CTE + """
                SELECT
                    source.id,
                    source.name,
                    COUNT(*) AS application_count,
                    SUM(CAST(flags.response_reached AS BIGINT)) AS response_count,
                    SUM(CAST(flags.interview_reached AS BIGINT)) AS interview_count,
                    SUM(CAST(flags.offer_reached AS BIGINT)) AS offer_count,
                    SUM(CAST(flags.rejection_reached AS BIGINT)) AS rejection_count
                FROM application_flags flags
                JOIN job_sources source ON source.id = flags.source_id
                WHERE flags.applied_date IS NOT NULL
                GROUP BY source.id, source.name
                """;
        return findDimensions(sql, progressionParameters(ownerId,
                responseStatuses, interviewStatuses, offerStatuses, rejectionStatuses));
    }

    public List<DimensionCounts> findTechnologies(
            Long ownerId,
            Collection<String> responseStatuses,
            Collection<String> interviewStatuses,
            Collection<String> offerStatuses,
            Collection<String> rejectionStatuses
    ) {
        String sql = APPLICATION_FLAGS_CTE + """
                SELECT
                    technology.id,
                    technology.name,
                    COUNT(*) AS application_count,
                    SUM(CAST(flags.response_reached AS BIGINT)) AS response_count,
                    SUM(CAST(flags.interview_reached AS BIGINT)) AS interview_count,
                    SUM(CAST(flags.offer_reached AS BIGINT)) AS offer_count,
                    SUM(CAST(flags.rejection_reached AS BIGINT)) AS rejection_count
                FROM application_flags flags
                JOIN job_application_technologies application_technology
                    ON application_technology.application_id = flags.id
                JOIN technologies technology
                    ON technology.id = application_technology.technology_id
                WHERE flags.applied_date IS NOT NULL
                GROUP BY technology.id, technology.name
                """;
        return findDimensions(sql, progressionParameters(ownerId,
                responseStatuses, interviewStatuses, offerStatuses, rejectionStatuses));
    }

    public List<Long> findFirstResponseDays(Long ownerId, Collection<String> responseStatuses) {
        String sql = """
                SELECT CAST(MIN(history.changed_at) AT TIME ZONE 'UTC' AS date)
                    - application.applied_date AS response_days
                FROM job_applications application
                JOIN application_status_history history
                    ON history.application_id = application.id
                WHERE application.owner_id = :ownerId
                  AND application.applied_date IS NOT NULL
                  AND history.status IN (:responseStatuses)
                GROUP BY application.id, application.applied_date
                """;
        return jdbcTemplate.query(sql, new MapSqlParameterSource()
                        .addValue("ownerId", ownerId)
                        .addValue("responseStatuses", responseStatuses),
                (resultSet, rowNumber) -> resultSet.getLong("response_days"));
    }

    private List<DimensionCounts> findDimensions(String sql, MapSqlParameterSource parameters) {
        return jdbcTemplate.query(sql, parameters,
                (resultSet, rowNumber) -> new DimensionCounts(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getLong("application_count"),
                        resultSet.getLong("response_count"),
                        resultSet.getLong("interview_count"),
                        resultSet.getLong("offer_count"),
                        resultSet.getLong("rejection_count")));
    }

    private MapSqlParameterSource progressionParameters(
            Long ownerId,
            Collection<String> responseStatuses,
            Collection<String> interviewStatuses,
            Collection<String> offerStatuses,
            Collection<String> rejectionStatuses
    ) {
        return new MapSqlParameterSource()
                .addValue("ownerId", ownerId)
                .addValue("responseStatuses", responseStatuses)
                .addValue("interviewStatuses", interviewStatuses)
                .addValue("offerStatuses", offerStatuses)
                .addValue("rejectionStatuses", rejectionStatuses);
    }

    public record SummaryCounts(
            long totalApplications,
            long appliedApplications,
            long responseCount,
            long interviewCount,
            long offerCount,
            long rejectionCount
    ) {
    }

    public record FunnelCount(String status, long applicationCount) {
    }

    public record TimeBucket(java.time.LocalDate startDate, long applicationCount) {
    }

    public record DimensionCounts(
            long id,
            String name,
            long applicationCount,
            long responseCount,
            long interviewCount,
            long offerCount,
            long rejectionCount
    ) {
    }
}
