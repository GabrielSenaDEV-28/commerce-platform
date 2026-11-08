# ADR 0002: Modular Monolith Architecture

## Status

Accepted

## Context

The platform is at an early stage, with a small set of business capabilities
and no requirement to deploy or scale parts of the system independently.
Applying a full ports-and-adapters structure to every use case would introduce
extra interfaces and package layers before there are multiple implementations
or infrastructure concerns to isolate.

The codebase still needs clear business boundaries so that the application can
grow without turning into a collection of unrelated classes or creating
uncontrolled dependencies between capabilities.

## Decision

The application will be built as a Modular Monolith: one deployable
application, organized into business modules in the same codebase.

Initial modules are `merchant` and `store`. Each module owns its business
rules and related application behavior. Use cases that coordinate capabilities
may depend on the collaborating module's required types, with dependencies
kept explicit and limited.

Organize code by business module first. Add further internal packages only
when they clarify a real responsibility. Repository contracts and other
abstractions belong with the module that owns them; do not introduce a port,
adapter, or interface solely to mirror a prescribed architecture.

Modules remain in the same application and share the deployment lifecycle.
Microservices, independent deployables, and infrastructure-per-module are not
part of this decision.

## Consequences

### Positive

- Keeps the initial project structure small and focused on business
  capabilities.
- Avoids duplicating interfaces and adapter classes without a concrete need.
- Preserves module-level ownership as the product grows.
- Allows internal organization or selective abstractions when real complexity
  justifies them.

### Negative

- Module boundaries are primarily maintained through package organization and
  code review rather than separate build artifacts.
- Cross-module dependencies require discipline to avoid coupling business
  internals.
- Introducing external integrations or multiple persistence implementations
  may require additional abstractions later.

## When to Revisit

Revisit the structure when a module gains distinct infrastructure concerns,
multiple implementations, substantially different scaling needs, or a team
ownership boundary that justifies stronger enforcement.
