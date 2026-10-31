# JdbcOdata

[![CI](https://github.com/karlkauc/JdbcOdata/actions/workflows/ci.yml/badge.svg)](https://github.com/karlkauc/JdbcOdata/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-karlkauc.github.io%2FJdbcOdata-2563eb)](https://karlkauc.github.io/JdbcOdata/)
[![Licence: MIT](https://img.shields.io/badge/licence-MIT-green)](LICENSE)

**Turn SQL queries against your databases into OData V4 feeds for Excel and Power BI, without
giving up your database permissions.**

You write a `SELECT` in a web UI. JdbcOdata publishes it as an OData feed. Excel and Power BI
connect with the database login of whoever is asking, so grants and row-level security keep
working exactly as they do in the database.

![Query editor with test run and schema snapshot](website/docs/assets/query-editor.png)

## What it's good for

- **Self-service reporting from production databases.** Publish curated queries instead of handing
  out direct database access.
- **Permissions stay in the database.** By default every request runs with the caller's own
  database login (HTTP Basic pass-through). Nothing is stored.
- **Less load on the database.** Results are cached per user, and filters can be pushed down to SQL
  query by query.
- **Knowing who ran what.** Every execution is logged with caller, query, duration, rows and errors.

Supports PostgreSQL, MySQL/MariaDB and Oracle (driver added by you, see below). Runs on Linux and
Windows as a single Java 21 JAR, or with Docker Compose.

## Try it

**Live demo:** <https://odata.status20.net/odata/> is open without a login. Browse it in the sister
project [OData Online Viewer](https://www.odata-viewer.online), which shows any OData service as a
tree, an ER diagram and a data explorer.

**Locally, with sample data** (Java 21):

```bash
./mvnw package -DskipTests
java -jar target/jdbcodata.jar --spring.profiles.active=demo
```

The demo listens on `127.0.0.1:8080` only. Then open:

| What | URL | Login |
|---|---|---|
| Admin UI | <http://localhost:8080/admin> | `admin`, password in `data/initial-admin-password` |
| OData feed | <http://localhost:8080/odata/> | database login `demo` / `demo` |

In Excel: *Data → Get Data → From Other Sources → From OData Feed*, then choose *Basic* and enter
the database login.

With Docker: `docker compose -f docker/docker-compose.demo.yml up -d`. The password is shown by
`docker exec jdbcodata-demo cat /app/data/initial-admin-password`.

The demo profile uses plain HTTP and is for your own machine only. For real use, read the
[operations guide](https://karlkauc.github.io/JdbcOdata/operations/).

## Documentation

The full documentation is at **<https://karlkauc.github.io/JdbcOdata/>**: quickstart, connecting
Excel and Power BI, admin guide, identity model, operations, configuration and security.

## Oracle

The Oracle JDBC driver is proprietary and therefore not bundled. Put `ojdbc11.jar` into `lib/`
(Docker: the `/app/lib` volume), or build for your own use with `./mvnw -Poracle clean package`.

## Security

Please report vulnerabilities privately, see [SECURITY.md](SECURITY.md). The threat model and known
limits are in the [security documentation](https://karlkauc.github.io/JdbcOdata/security/).

## Contributing

Issues and pull requests are welcome, see [CONTRIBUTING.md](CONTRIBUTING.md).

## Sister tools

- [OData Online Viewer](https://www.odata-viewer.online) – explore any OData service in the browser
- [XSD Viewer](https://www.xsd-viewer.online) and [XML Viewer](https://www.xml-viewer.online)

## Licence

[MIT](LICENSE). Bundled libraries keep their own licences, see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
