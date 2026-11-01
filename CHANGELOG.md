# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/), versions follow
[Semantic Versioning](https://semver.org/).

## [1.1.0] – unreleased

### Added

- Optional usage statistics for public instances, off unless `jdbcodata.usage.db-url` is set. Self-hosted
  installations record and send nothing
- Connections per login are configurable for each datasource (`poolSize`, 1 to 100, default 2 as before). All
  callers of a technical account share one pool, so its parallel executions were limited to 2

### Fixed

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
