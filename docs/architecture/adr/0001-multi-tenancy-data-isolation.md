# ADR 0001: Multi-Tenancy Data Isolation Strategy

## Status

Accepted

## Context

The platform is designed as a multi-tenant SaaS where multiple merchants operate
independent stores using the same application.

Each tenant owns business data such as products, inventory, customers, orders,
and payments. The platform must guarantee logical isolation between tenants
while keeping the initial architecture operationally simple.

Different strategies can be used to isolate tenant data:

1. Shared database and shared schema with a tenant identifier.
2. Shared database with a dedicated schema per tenant.
3. Dedicated database per tenant.

At the current stage, the platform has no requirement for physical database
isolation, tenant-specific infrastructure, or independent database lifecycle.

The architecture should favor simplicity while allowing the isolation strategy
to evolve if future scale, compliance, or enterprise requirements justify it.

## Decision

The platform will initially use a shared PostgreSQL database and shared schema.

Business data owned by a tenant will include a tenant identifier that defines
which tenant owns that data.

Tenant context will be resolved from the incoming request and propagated
through the application so that tenant-owned operations are executed within
the correct tenant boundary.

The initial architecture will not use schema-per-tenant or
database-per-tenant isolation.

## Consequences

### Positive

- Database provisioning remains simple as new tenants are created.
- Database migrations can be applied once instead of being coordinated across
  multiple schemas or databases.
- Infrastructure and operational complexity remain low during the initial
  stages of the platform.
- Shared infrastructure makes it practical to support a growing number of
  small and medium-sized tenants.
- The strategy can later evolve if stronger isolation is required for specific
  tenants.

### Negative

- Tenant isolation is primarily logical rather than physical.
- Incorrectly scoped queries may create a risk of cross-tenant data access.
- Application and database design must consistently enforce tenant boundaries.
- Indexes and uniqueness constraints may need to include tenant identity.
- Future migration of specific tenants to dedicated infrastructure would
  require additional architectural work.

## Alternatives Considered

### Schema per Tenant

Each tenant would have a dedicated PostgreSQL schema containing its business
tables.

This provides stronger structural separation but increases operational
complexity. Schema creation, migrations, monitoring, and maintenance must be
coordinated across an increasing number of tenant schemas.

This complexity is not justified by the current requirements.

### Database per Tenant

Each tenant would have a dedicated database.

This provides stronger isolation and greater flexibility for tenant-specific
operations, backups, scaling, and compliance requirements.

However, it significantly increases infrastructure, connection management,
provisioning, migration, monitoring, and operational complexity.

The current platform requirements do not justify this level of isolation.

## Future Considerations

The isolation strategy may be revisited if future requirements introduce:

- Enterprise tenants requiring dedicated infrastructure.
- Regulatory or compliance requirements demanding stronger isolation.
- Tenant-specific backup or restore requirements.
- Data residency requirements.
- Performance or scaling constraints that justify distributing tenants across
  multiple databases.

A hybrid strategy may eventually be adopted, keeping most tenants on shared
infrastructure while allowing selected tenants to use dedicated databases.