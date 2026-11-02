# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/), versions follow
[Semantic Versioning](https://semver.org/).

## [1.1.0] – unreleased

### Added

- Optional usage statistics for public instances, off unless `jdbcodata.usage.db-url` is set. Self-hosted
  installations record and send nothing
- Connections per login are configurable for each datasource (`poolSize`, 1 to 100, default 2 as before). All
  callers of a technical account share one pool, so its parallel executions were limited to 2
- The audit log now records admin sign-ins and sign-outs, rejected admin and feed logins, and query test runs
- `jdbcodata.audit.retention-days` deletes old audit records. The default `0` keeps everything
- Every request has an ID in its log lines and in the `X-Request-Id` response header. JSON logs are available with
  `logging.structured.format.console=ecs`
- `jdbcodata.security.metrics-token` lets Prometheus scrape without an admin login
- Metrics for identity pools, running executions, rate-limited requests and dropped log records
- Releases carry an SBOM, checksums and cosign signatures. The Docker image is also built for `linux/arm64`
- Single sign-on for the admin UI with OpenID Connect, tested against Keycloak. Roles come from a claim, sign-out also
  ends the session at the identity provider, and `jdbcodata.security.oidc.local-login=false` turns off password logins.
  Feeds still use Basic Auth
- New admin accounts and passwords set by another admin are temporary: the owner must choose their own at the first
  sign-in, and until then the REST API answers 403. Existing accounts are not affected. The generated initial password
  is temporary too; a password from `jdbcodata.admin.password` is not. All local accounts can change their own password
- API keys for machine clients: stored as a hash, with an expiry, and revocable at once. A key reads feeds as a service
  user or calls the REST API with a role. Excel uses it as password of the user `apikey`

### Fixed

- With several instances, cache invalidations and datasource changes did not reach the other instances: they kept
  answering from memory until the entry expired, and kept using connection pools for the old database. Invalidations now
  arrive within `jdbcodata.cluster.poll-interval-ms`, datasource changes at once
- Many clients refreshing one cached feed could exhaust the heap: cache hits skipped the concurrency limit, so each
  built its complete response in memory at the same time. `jdbcodata.odata.response-rows-budget` now limits these
  responses; further clients wait up to `jdbcodata.odata.response-wait-ms` and then get 503
- Oracle: connections were not read-only, because the Oracle driver accepts `setReadOnly` without enforcing it. Only
  the check that SQL is a `SELECT` protected the data. Each connection now starts a read-only transaction. A function
  that writes in an autonomous transaction can still do so, so database permissions remain the real limit. Queries
  on a table can fail with `ORA-01466` for a few seconds after DDL on it
- MySQL and MariaDB push-down: string comparisons ignored trailing blanks, and `in` compared by the column's
  collation, usually ignoring case. Both returned wrong rows. String grouping, `countdistinct`, `min` and `max`
  now also count values with trailing blanks separately
- MySQL and MariaDB push-down: grouped `TINYINT(1)` columns came back as numbers instead of booleans
- Pass-through users with accounts on only some datasources paid for a failed login on the others with every
  request, which cost seconds and could lock accounts. A rejected login is now remembered for 5 minutes
- With anonymous access on, a request with a Bearer token or a malformed Basic header ran as the anonymous user.
  It now gets 401, so a failed login no longer looks like a working one with other data

## [1.0.0] – 2026-10-31

First public release.

### Added

- Read-only OData V4 feeds from SQL queries, managed in an admin UI and a REST API
- HTTP Basic pass-through to the database, stored credentials for service users, FIXED technical accounts
- Per-user result cache, execution statistics, audit log, Prometheus metrics
- Query push-down, server-driven paging, delta links, async requests, `$apply`, `$compute`, `$search`, `$batch`
- PostgreSQL, MySQL/MariaDB and Oracle datasources; Docker Compose setup and distribution ZIP

### Security

- The default limit is 100,000 rows per result and 100 requests per `$batch`
- The `demo` profile listens on `127.0.0.1` only
- H2 over TCP/SSL is no longer allowed by default and the embedded-H2 check follows H2's own URL parsing
- Admin passwords below 8 characters stop the start outside the `demo` profile
- Pass-through logins are no longer accepted while a database is unreachable

### Changed

- The Oracle JDBC driver is no longer bundled; add it to `lib/` or build with `-Poracle`
