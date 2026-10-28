# Independent cloud product foundations

Updated 28 October 2026. The complete independent-products plan is not implemented.

This application remains an independent deployment/database per business. The optional business management API does not require a registered POS Master. Existing website/customer authentication is retained; generic administrators and customers are never automatically promoted to business Owner.

## Migrations

Run migrations through the existing protected migration profile and deployment credentials, after a verified PostgreSQL backup. No live migration was performed during implementation.

For a recognized legacy migration.sql history, validate its original checksum and retain existing records. New histories use SQL files/empty-schema-v1.sql, which creates structure without restaurant catalogue, branches or default account seeds. The restaurant modifier maintenance runner is restricted to postgres-test fixtures and no longer runs under migrate. The commercial-identity-v1.sql addition creates the deployment business UUID, stable UUIDs for existing branch IDs and optional business management boundaries. Historical names, IDs and references remain unchanged; unknown historical creation times remain unknown.

Do not edit applied migration sources, truncate historical data or run the full website seed script as an installation shortcut. Application rollback retains additive schema, mappings and history.

## Interfaces currently available

GET /api/branches exposes active branch IDs/names using the existing website directory abstraction.

With app.business-management.enabled=true, GET /api/business-management/stores and POST to that path expose revisioned, idempotent store management. POST includes commandId, businessId, nullable storeId/expectedRevision and a values object. Creation generates numeric and UUID identity. Existing signed database actor context is checked against an explicit enabled business_owner record. Stores cannot be managed through a foreign deployment business ID. Restricted database functions persist the store change, command receipt, management audit and configuration change together.

The first-Owner protected bootstrap flow, website management screens, central cloud-staff/PIN administration, terminal pairing, initial catalogue replacement and incremental synchronization are outstanding. Do not grant owner status by inferring customer email/name or existing generic roles. The new business_owner ledger alone is not an enrollment user flow.

Configuration stream rows and logical clocks are foundations, not an operating delivery service. No POS pairing credentials, plaintext PIN distribution, transaction sync or production failover were introduced. Live deployment remains a separately approved operation.

## Verification

Isolated cloud tests cover existing migration history, customer/website RLS regressions, explicit owner versus manager authorization, generated third-store identity, retry idempotency, foreign-business rejection and unchanged order counts. A separate empty isolated database was migrated without restaurant/customer seeds. Native key-storage and Windows acceptance remain unverified. POS documentation describes the corresponding local-store foundations and remaining security/integration work.
