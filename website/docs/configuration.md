# Configuration reference

Settings live in `config/application.yml` next to the JAR, or in environment variables. Spring Boot maps `jdbcodata.cache.max-size-mb` to
`JDBCODATA_CACHE_MAX_SIZE_MB` and `server.port` to `SERVER_PORT`.

## Server

| Key | Default | Meaning |
|---|---|---|
| `server.port` | `8080` | HTTP(S) port |
| `server.address` | all interfaces | Bind address. The `demo` profile sets `127.0.0.1` |
| `server.ssl.key-store` | none | PKCS12 keystore for built-in TLS, with `key-store-password` and `key-store-type: PKCS12` |
| `server.forward-headers-strategy` | none | Use `native` behind a reverse proxy. Tomcat then trusts `X-Forwarded-*` only from internal proxy addresses. Other proxy networks: `server.tomcat.remoteip.internal-proxies` |
| `server.servlet.session.cookie.same-site` | `lax` | SameSite of the admin session cookie. Tomcat sets `Secure` on HTTPS |
| `management.endpoints.web.exposure.include` | `health,prometheus` | Actuator endpoints. `/actuator/prometheus` needs an admin account |
| `logging.file.name` | none | For example `logs/jdbcodata.log` |

## Admin and storage

| Key | Default | Meaning |
|---|---|---|
| `jdbcodata.admin.user` | `admin` | Name of the first admin account, used only at the first start |
| `jdbcodata.admin.password` | empty | Password of the first admin account. Empty: generated into `<data-dir>/initial-admin-password` (mode 600), never logged. Outside the `demo` profile, under 8 characters or a `change-me…` placeholder stops the start |
| `jdbcodata.master-key` | empty | AES-256 key (Base64, 32 bytes) for stored database passwords. Empty: key file `<data-dir>/master.key` |
| `jdbcodata.data-dir` | `./data` | Directory for key file and generated password. The default H2 metadata and demo database paths are set separately and also use `./data` |
| `jdbcodata.meta.url` | H2 file in `./data` | JDBC URL of the metadata database (H2 or PostgreSQL) |
| `jdbcodata.meta.username`, `jdbcodata.meta.password` | `sa`, empty | Login of the metadata database |
| `jdbcodata.seed.file` | empty | YAML with datasources and queries, upserted by name at start |

## OData

| Key | Default | Meaning |
|---|---|---|
| `jdbcodata.odata.max-rows` | `100000` | Hard limit per result. Datasource and query can only lower it. Results are held in memory |
| `jdbcodata.odata.default-timeout-sec` | `60` | Timeout if the datasource sets none |
| `jdbcodata.odata.concurrency-wait-ms` | `2000` | Wait for a free execution slot of the datasource, then 503 |
| `jdbcodata.odata.page-size` | `0` | Server-driven paging, rows per response. `0` is off. Without the result cache, data can change between pages; see [Paging](odata-reference.md#paging) |
| `jdbcodata.odata.anonymous-user` | empty | Service user for requests without an `Authorization` header. Empty: every request needs Basic Auth. See [Anonymous access](identity.md#anonymous-access) |

## Cache and statistics

| Key | Default | Meaning |
|---|---|---|
| `jdbcodata.cache.max-size-mb` | `1024` | Total size of the persistent cache, compressed |
| `jdbcodata.cache.memory-mb` | `256` | In-memory layer |
| `jdbcodata.cache.cleanup-interval-ms` | `300000` | Cleanup job for expired entries and the size limit |
| `jdbcodata.stats.retention-days` | `90` | Retention of the execution log |
| `jdbcodata.stats.async` | `true` | Write the log on a separate thread |
| `jdbcodata.health.interval-ms` | `60000` | Active check of datasources with a technical account. `0` is off |
| `jdbcodata.ui.environment` | empty | Label in the admin top bar, for example `PROD` |

## Usage statistics

Off by default. See [Usage statistics](security.md#usage-statistics).

| Key | Default | Meaning |
|---|---|---|
| `jdbcodata.usage.db-url` | empty | PostgreSQL URL of a separate statistics database. Empty: nothing is recorded or sent |
| `jdbcodata.usage.db-user` | empty | Login of that database |
| `jdbcodata.usage.db-password` | empty | Password of that database |
| `jdbcodata.usage.hash-secret` | empty | Required with `db-url`. Keys the daily salt of the visitor hash |

## Security

| Key | Default | Meaning |
|---|---|---|
| `jdbcodata.security.allow-plain-http` | `false` | Allow starting without TLS. Only behind a TLS proxy or locally |
| `jdbcodata.security.rate-limit-per-minute` | `20` | Failed logins per client IP before 429. Per user name, five times that. A counter expires one minute after the last failure. `0` turns it off |
| `jdbcodata.security.allowed-jdbc-urls` | empty | Further JDBC URL prefixes, for example `jdbc:sqlserver:`. Always allowed: `jdbc:postgresql:`, `jdbc:mariadb:`, `jdbc:oracle:thin:` |
| `jdbcodata.security.allow-embedded-h2` | `false` | Allow embedded H2 (`jdbc:h2:mem:`, `jdbc:h2:file:`) as a datasource. It runs inside the service and could read its files, so use it for the demo and tests only |

## Profiles

`demo` creates a local H2 sample database, loads the sample query, allows plain HTTP and embedded H2, and binds to `127.0.0.1`. Docker's demo compose file overrides the bind
address inside the container and publishes the port on `127.0.0.1` only.
