ALTER TABLE account_tokens DROP CONSTRAINT ck_account_tokens_purpose;
ALTER TABLE account_tokens ADD CONSTRAINT ck_account_tokens_purpose CHECK (
    purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET', 'PASSWORD_SETUP', 'ACCOUNT_DELETION')
);

ALTER TABLE account_email_outbox DROP CONSTRAINT ck_account_email_outbox_purpose;
ALTER TABLE account_email_outbox ADD CONSTRAINT ck_account_email_outbox_purpose CHECK (
    purpose IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET', 'PASSWORD_SETUP', 'ACCOUNT_DELETION')
);
