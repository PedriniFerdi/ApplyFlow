INSERT INTO job_sources (name)
SELECT source.name
FROM (VALUES
    ('LinkedIn'),
    ('YC Work at a Startup'),
    ('Referral'),
    ('Company Website'),
    ('Indeed'),
    ('Get on Board'),
    ('Other')
) AS source(name)
WHERE NOT EXISTS (
    SELECT 1 FROM job_sources existing WHERE LOWER(existing.name) = LOWER(source.name)
);

INSERT INTO technologies (name)
SELECT technology.name
FROM (VALUES
    ('Java'),
    ('Spring Boot'),
    ('C#'),
    ('.NET'),
    ('React'),
    ('SQL Server'),
    ('PostgreSQL'),
    ('AWS'),
    ('Docker')
) AS technology(name)
WHERE NOT EXISTS (
    SELECT 1 FROM technologies existing WHERE LOWER(existing.name) = LOWER(technology.name)
);
