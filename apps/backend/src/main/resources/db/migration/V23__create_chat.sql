-- Driver-owned read projection for chat. No credentials or private profile fields.
CREATE VIEW driver_chat_directory AS
SELECT driver_id, account_id, account_status, worker_name
FROM drivers;

CREATE TABLE chat_conversations (
    conversation_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    driver_id BIGINT NOT NULL UNIQUE REFERENCES drivers(driver_id),
    created_at TIMESTAMPTZ NOT NULL,
    admin_read_message_id BIGINT,
    admin_read_at TIMESTAMPTZ,
    driver_read_message_id BIGINT,
    driver_read_at TIMESTAMPTZ,
    CHECK ((admin_read_message_id IS NULL) = (admin_read_at IS NULL)),
    CHECK ((driver_read_message_id IS NULL) = (driver_read_at IS NULL))
);

CREATE TABLE chat_messages (
    message_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES chat_conversations(conversation_id),
    sender_account_id BIGINT NOT NULL REFERENCES identity_accounts(account_id),
    sender VARCHAR(6) NOT NULL CHECK (sender IN ('ADMIN', 'DRIVER')),
    idempotency_key VARCHAR(200) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    body TEXT NOT NULL CHECK (char_length(body) BETWEEN 1 AND 2000),
    sent_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    UNIQUE (conversation_id, sender_account_id, idempotency_key),
    UNIQUE (conversation_id, message_id)
);

ALTER TABLE chat_conversations
    ADD FOREIGN KEY (conversation_id, admin_read_message_id) REFERENCES chat_messages(conversation_id, message_id),
    ADD FOREIGN KEY (conversation_id, driver_read_message_id) REFERENCES chat_messages(conversation_id, message_id);

CREATE INDEX ix_chat_messages_unread ON chat_messages(conversation_id, sender, message_id) WHERE read_at IS NULL;

CREATE TABLE chat_message_events (
    message_id BIGINT PRIMARY KEY REFERENCES chat_messages(message_id) ON DELETE CASCADE,
    processed_at TIMESTAMPTZ
);

CREATE INDEX ix_chat_events_pending ON chat_message_events(message_id) WHERE processed_at IS NULL;

CREATE TABLE chat_push_deliveries (
    delivery_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id BIGINT NOT NULL REFERENCES chat_message_events(message_id) ON DELETE CASCADE,
    account_id BIGINT NOT NULL REFERENCES identity_accounts(account_id),
    installation_id VARCHAR(64) NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROVIDER_ACCEPTED', 'FAILED', 'SKIPPED')),
    UNIQUE (message_id, installation_id),
    CHECK (status = 'PENDING' OR lease_until IS NULL)
);
CREATE INDEX ix_chat_push_pending ON chat_push_deliveries(due_at, delivery_id) WHERE status = 'PENDING';
