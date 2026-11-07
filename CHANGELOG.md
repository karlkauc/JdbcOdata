# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/), versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.2.0] – 2026-11-07

### Added

- OpenAPI 3.1 description of the admin API at `/api/v1/openapi` (YAML at `/api/v1/openapi.yaml`), with the same
  login as the API itself. Error responses are described as problem details
- Configuration export and import for moving datasources, technical accounts, groups and queries (with snapshot,
  column settings and grants) between environments: *System → Export / import* in the admin UI and
  `GET /api/v1/config`, `POST /api/v1/config/import`. The import shows what it would change before it applies the
  file, keeps the target's connection data unless told otherwise, deletes only with `prune` and changes nothing when
  an entry fails. Exports contain no passwords and no service users

### Fixed

- The OData metadata and the result cache are dropped after a change is committed, not before. A request in between
  could rebuild the metadata from the old definition and keep it. Connection pools of a changed datasource are closed
  after the commit as well

## [1.1.0] – 2026-11-04

### Added

- Secrets can come from files: `JDBCODATA_MASTER_KEY_FILE=/run/secrets/key` works for every `JDBCODATA_*` and `SPRING_*`
  variable, and Spring's `configtree` is documented for mounted Kubernetes secrets and Vault Agent
- Helm chart in `deploy/helm/jdbcodata` with probes, resource limits, PodDisruptionBudget, Secret references, optional
  ServiceMonitor, alert rules and Grafana dashboard
- The master key can be replaced: `jdbcodata.master-key-previous` lists older keys that only decrypt, and stored
  passwords move to the current key at startup. Passwords that cannot be read no longer stop the start
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
- `jdbcodata.security.require-datasource-tls` only allows datasource connections that verify the server certificate
  (PostgreSQL and MariaDB/MySQL `verify-full` or `verify-ca`, Oracle `tcps`). It is checked on save, on the connection
  test and when a pool opens, so datasources saved before it was turned on are covered too
- Circuit breaker per datasource: after 5 failed connection attempts within 120 seconds a datasource answers 503 with
  `Retry-After` for 30 seconds instead of blocking every request for the connection timeout. Wrong passwords, SQL
  errors and full pools do not count. Shown as "unreachable" in the admin UI, with metric and alert rule
- `jdbcodata.odata.max-response-mb` (default 256) limits the size of a response, larger ones get 413
- OpenTelemetry tracing, off unless `management.tracing.export.enabled=true`: one trace per request with a span per
  database access, sent over OTLP. Spans carry no SQL, parameter values, user names or database error texts
- Executions record the bytes sent, shown in the statistics page, the API and the CSV export (new last column `bytes`)
- A reproducible load test in `loadtest/` with measurements in `docs/OPERATIONS.md`

### Changed

- Results of asynchronous requests are stored in the metadata database, compressed and encrypted, for one hour. Every instance can
  answer the status monitor, and a request whose instance stopped is reported with 503 instead of running forever
- Admin UI sessions are stored in the metadata database. They work on every instance, survive restarts, and disabling
  an account ends its sessions everywhere, not only on the instance that made the change. The session cookie is now
  called `SESSION`, so everybody has to sign in once after the update
- With PostgreSQL as metadata database the service refuses to start without `JDBCODATA_MASTER_KEY`: a key file would
  differ per instance, and passwords stored by one could not be read by another. **When upgrading** an installation that
  uses `data/master.key`, set `JDBCODATA_MASTER_KEY` to the content of that file. The Docker Compose setup already does
- Responses are written to a spool file (in memory up to 1 MB, above that encrypted on disk) instead of a byte array on
  the heap. `jdbcodata.odata.spool-max-mb` limits the disk space of all responses together, above it clients get 503
- Reading a whole entity set, as Excel and Power BI do on every refresh, builds entities only for the page that is
  sent. Cache hits on this path no longer count against `response-rows-budget`
- Without query cache, paging and `$count`, a whole entity set is read through a database cursor and never held in
  the heap. **On this path `jdbcodata.odata.max-rows` no longer applies**; the limits are `max_rows` of the datasource
  (default 500,000), the limit of the query and `max-response-mb`. The query timeout now covers the whole read, not
  only its start, so a read that took longer than the timeout (default 60 s) now ends with 504
- The Docker image applies current Alpine package updates when it is built, so a fix does not wait for a new
  Temurin base image

### Fixed

- Instances starting at the same time on an empty metadata database could fail on unique constraints while creating the
  first admin or applying the seed. Startup work now runs on one instance at a time
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
- `jdbcodata.health.interval-ms=0`, documented as "off", stopped the service from starting
- MariaDB error 1969 (`max_statement_time`) is now treated as a timeout

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
