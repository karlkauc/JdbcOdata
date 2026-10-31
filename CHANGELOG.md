# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/), versions follow
[Semantic Versioning](https://semver.org/).

## [1.0.0] – unreleased

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
