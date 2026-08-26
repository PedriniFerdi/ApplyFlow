CREATE INDEX ix_account_tokens_expires_retention
    ON account_tokens (expires_at, id);

CREATE INDEX ix_account_tokens_consumed_retention
    ON account_tokens (consumed_at, id)
    WHERE consumed_at IS NOT NULL;

CREATE INDEX ix_account_email_outbox_terminal_retention
    ON account_email_outbox (COALESCE(sent_at, discarded_at), id)
    WHERE (sent_at IS NOT NULL OR discarded_at IS NOT NULL) AND claim_token IS NULL;
