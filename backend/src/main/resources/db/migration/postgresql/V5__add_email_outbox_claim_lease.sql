ALTER TABLE account_email_outbox ADD COLUMN claim_token UUID NULL;
ALTER TABLE account_email_outbox ADD COLUMN claimed_at TIMESTAMP(3) WITH TIME ZONE NULL;

CREATE INDEX ix_account_email_outbox_claim_lease
    ON account_email_outbox (claimed_at)
    WHERE sent_at IS NULL AND discarded_at IS NULL;
