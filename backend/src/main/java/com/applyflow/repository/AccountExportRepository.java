package com.applyflow.repository;

import java.sql.ResultSet;
import java.util.function.Consumer;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.applyflow.exception.ResourceNotFoundException;

@Repository
public class AccountExportRepository {

    private final JdbcTemplate jdbc;

    public AccountExportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String profile(Long ownerId) {
        try {
            return jdbc.queryForObject("""
                    SELECT json_build_object('id', id, 'fullName', full_name, 'email', email,
                        'emailVerified', email_verified_at IS NOT NULL, 'createdAt', created_at, 'updatedAt', updated_at)::text
                    FROM users WHERE id = ?
                    """, String.class, ownerId);
        } catch (EmptyResultDataAccessException exception) {
            throw new ResourceNotFoundException("Account not found");
        }
    }

    public void stream(Section section, Long ownerId, Consumer<String> row) {
        jdbc.query(connection -> {
            var statement = connection.prepareStatement(section.sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            statement.setLong(1, ownerId);
            statement.setFetchSize(200);
            return statement;
        }, (org.springframework.jdbc.core.RowCallbackHandler) result -> row.accept(result.getString(1)));
    }

    public enum Section {
        COMPANIES("companies", """
                SELECT json_build_object('id', id, 'name', name, 'website', website, 'companyType', company_type, 'industry', industry)::text
                FROM companies WHERE owner_id = ? ORDER BY id
                """),
        APPLICATIONS("applications", """
                SELECT json_build_object('id', id, 'companyId', company_id, 'positionTitle', position_title, 'jobUrl', job_url,
                    'appliedDate', applied_date, 'status', status, 'sourceId', source_id, 'workMode', work_mode, 'location', location,
                    'salaryMin', salary_min::text, 'salaryMax', salary_max::text, 'currency', currency, 'salaryPeriod', salary_period,
                    'notes', notes, 'createdAt', created_at, 'updatedAt', updated_at)::text
                FROM job_applications WHERE owner_id = ? ORDER BY id
                """),
        HISTORY("history", """
                SELECT json_build_object('id', h.id, 'applicationId', h.application_id, 'status', h.status, 'changedAt', h.changed_at)::text
                FROM application_status_history h JOIN job_applications a ON a.id = h.application_id WHERE a.owner_id = ? ORDER BY h.id
                """),
        TECHNOLOGIES("technologies", """
                WITH owner AS (SELECT ?::bigint AS id)
                SELECT json_build_object('id', t.id, 'name', t.name, 'shared', t.owner_id IS NULL)::text FROM technologies t CROSS JOIN owner
                WHERE t.owner_id = owner.id OR (t.owner_id IS NULL AND EXISTS (
                    SELECT 1 FROM job_application_technologies l JOIN job_applications a ON a.id = l.application_id
                    WHERE a.owner_id = owner.id AND l.technology_id = t.id))
                ORDER BY t.id
                """),
        APPLICATION_TECHNOLOGIES("applicationTechnologies", """
                SELECT json_build_object('applicationId', l.application_id, 'technologyId', l.technology_id)::text
                FROM job_application_technologies l JOIN job_applications a ON a.id = l.application_id
                JOIN technologies t ON t.id = l.technology_id AND (t.owner_id IS NULL OR t.owner_id = a.owner_id)
                WHERE a.owner_id = ? ORDER BY l.application_id, l.technology_id
                """),
        SOURCES("sources", """
                SELECT json_build_object('id', s.id, 'name', s.name)::text FROM job_sources s
                WHERE EXISTS (SELECT 1 FROM job_applications a WHERE a.source_id = s.id AND a.owner_id = ?) ORDER BY s.id
                """);

        private final String sql;
        private final String fieldName;

        Section(String fieldName, String sql) {
            this.fieldName = fieldName;
            this.sql = sql;
        }

        public String fieldName() {
            return fieldName;
        }
    }
}
