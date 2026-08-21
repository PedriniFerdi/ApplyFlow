CREATE TABLE auth_rate_limits (
    bucket_key VARCHAR(128) NOT NULL,
    window_started_at TIMESTAMP(3) WITH TIME ZONE NOT NULL,
    attempts INTEGER NOT NULL,
    CONSTRAINT pk_auth_rate_limits PRIMARY KEY (bucket_key),
    CONSTRAINT ck_auth_rate_limits_attempts CHECK (attempts > 0)
);

CREATE INDEX ix_auth_rate_limits_window_started_at ON auth_rate_limits (window_started_at);

CREATE TABLE account_email_outbox (
    id UUID NOT NULL,
    account_token_id BIGINT NOT NULL,
    email VARCHAR(254) NOT NULL,
    full_name VARCHAR(160) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    encrypted_token TEXT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP(3) WITH TIME ZONE NULL,
    discarded_at TIMESTAMP(3) WITH TIME ZONE NULL,
    last_error VARCHAR(500) NULL,
    CONSTRAINT pk_account_email_outbox PRIMARY KEY (id),
    CONSTRAINT fk_account_email_outbox_token FOREIGN KEY (account_token_id)
        REFERENCES account_tokens (id) ON DELETE CASCADE,
    CONSTRAINT ck_account_email_outbox_purpose CHECK (
        purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET', 'PASSWORD_SETUP')
    )
);

CREATE INDEX ix_account_email_outbox_pending
    ON account_email_outbox (next_attempt_at)
    WHERE sent_at IS NULL AND discarded_at IS NULL;
