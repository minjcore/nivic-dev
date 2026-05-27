-- Mcs multi-tenant orders (Postgres path). Maps: tenant_id = mid, bill_number = Wire request_id or internal bill id.
-- Run as migration role; app role must NOT have BYPASSRLS.

CREATE TABLE tenant_bills (
    id                BIGSERIAL,
    tenant_id         INT NOT NULL,
    bill_number       BIGINT NOT NULL,
    gateway_order_id  TEXT,
    amount            BIGINT NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    paid_at           TIMESTAMPTZ,
    paid_by_uid       INT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, bill_number),
    CONSTRAINT tenant_bills_status_chk
        CHECK (status IN ('PENDING', 'PAID', 'CANCELLED'))
);

CREATE INDEX idx_tenant_bills_pending
    ON tenant_bills (tenant_id, bill_number)
    WHERE status = 'PENDING';

CREATE INDEX idx_tenant_bills_gateway_order
    ON tenant_bills (tenant_id, gateway_order_id)
    WHERE gateway_order_id IS NOT NULL;

ALTER TABLE tenant_bills ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_bills FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_bill_isolation_policy ON tenant_bills
    FOR ALL
    USING (
        tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::INT
    )
    WITH CHECK (
        tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::INT
    );
