ALTER TABLE job_applications
    ADD CONSTRAINT ck_job_applications_notes_length
    CHECK (notes IS NULL OR char_length(notes) <= 5000);
