# Operations

## Requirements

- Java 21, or Docker 24+ with Compose
- Network access from the service to the target databases
- A host name and a certificate. Basic Auth sends passwords, so plain HTTP is only acceptable locally

## Native install (Linux, Windows)

1. Unpack `jdbcodata-<version>-dist.zip`, created by `./mvnw package` in `target/`.
2. Copy `config/application-example.yml` to `config/application.yml` and adjust port, TLS keystore, metadata database, admin password and
   master key (`openssl rand -base64 32`).
3. Start `bin/jdbcodata.sh` or `bin\jdbcodata.cmd`. The first start creates `data/`, the metadata database and the admin account.
4. Open `https://host:8443/admin`, sign in, and create a datasource and a query.

If you leave the admin password empty, a random one is written to `data/initial-admin-password` (mode 600) and never logged. Delete that file after the
first login. A configured password shorter than 8 characters stops the start.

The start script sets `-Xms256m -Xmx1g`. Override it with `JAVA_OPTS`. Results are held in memory, so raise the heap together with `max-rows`.

To run it as a service, use `contrib/jdbcodata.service` (systemd) or `contrib/jdbcodata-winsw.xml` (Windows, WinSW). Secrets belong in
`/etc/jdbcodata/jdbcodata.env` (template `contrib/jdbcodata.env.example`, mode 600). The systemd unit also sets `UMask=0077`, so `data/` stays private.
If you start the service differently, set a restrictive umask yourself.

### Built-in TLS

```bash
# self-signed for testing; in production export a certificate of your CA as PKCS12
export SERVER_SSL_KEY_STORE_PASSWORD="$(openssl rand -base64 24)"
keytool -genkeypair -alias jdbcodata -keyalg RSA -keysize 2048 -validity 365 \
  -dname "CN=odata.example.org" -keystore certs/jdbcodata.p12 -storetype PKCS12 \
  -storepass "$SERVER_SSL_KEY_STORE_PASSWORD"
```

```yaml
server:
  port: 8443
  ssl:
    key-store: certs/jdbcodata.p12
    key-store-type: PKCS12    # password from SERVER_SSL_KEY_STORE_PASSWORD, not in this file
```

Without `server.ssl.*`, the service starts only with `jdbcodata.security.allow-plain-http: true`, and logs a warning.

## Docker Compose

```bash
cd docker
cp .env.example .env    # host, admin password, master key, PostgreSQL password
docker compose up -d --build
docker compose logs -f jdbcodata
```

- `caddy` terminates TLS on port 443. `JDBCODATA_HOST=localhost` with `JDBCODATA_TLS=internal` gives a self-signed certificate; import the Caddy CA on Excel
  clients. For a public host, put an e-mail address into `JDBCODATA_TLS` for Let's Encrypt, or mount your own certificate
  (`JDBCODATA_TLS="/certs/cert.pem /certs/key.pem"` plus a volume at `/certs`).
- `jdbcodata` uses PostgreSQL as metadata database and is not published to the host. Only Caddy is.
- `./config` and `./lib` are mounted read-only: `config/application.yml` for settings, `lib/` for JDBC drivers.
- `JDBCODATA_HTTP_PORT` and `JDBCODATA_HTTPS_PORT` set the host ports.

To update, raise `JDBCODATA_VERSION` in `.env` and run `docker compose up -d --build`. Flyway migrates the schema at start.

!!! note
    The Caddy example publishes the whole service, including `/admin` and `/api`. Restrict these to known networks in Caddy where you can.

## Behind a reverse proxy

Apache, nginx, Traefik or IIS can terminate TLS instead. Run the service on `127.0.0.1` with `JDBCODATA_SECURITY_ALLOW_PLAIN_HTTP=true` and
`SERVER_FORWARD_HEADERS_STRATEGY=native`. The proxy sets `X-Forwarded-Proto`, `X-Forwarded-Port` and `X-Forwarded-For`, and must drop these headers when they
come from the client. Otherwise foreign hosts end up in `@odata.context` and `@odata.nextLink`.

Use `native`, not `framework`. With `framework`, the first `X-Forwarded-For` entry counts, and clients can forge it to evade the rate limit.

Apache needs two settings for OData URLs, or it breaks `$filter`, parentheses and percent-encoded characters:

```apache
AllowEncodedSlashes NoDecode
ProxyPreserveHost On
ProxyPass        /actuator/ !
ProxyPass        / http://127.0.0.1:8080/ nocanon disablereuse=On
ProxyPassReverse / http://127.0.0.1:8080/
RequestHeader set X-Forwarded-Proto "https"
RequestHeader set X-Forwarded-Port  "443"
RequestHeader unset X-Forwarded-Host early
```

A complete example with Docker, Let's Encrypt and a sample PostgreSQL is in `deploy/` in the repository.

## Verifying a release

Releases from 1.1.0 on are signed without a long-lived key: [cosign](https://docs.sigstore.dev/) records that the release workflow of this repository built them. Each release also carries an SBOM in CycloneDX format (`jdbcodata-sbom.json`).

```bash
# files: download SHA256SUMS and SHA256SUMS.sigstore.json from the release, next to the JAR and ZIP
cosign verify-blob SHA256SUMS --bundle SHA256SUMS.sigstore.json \
  --certificate-identity-regexp '^https://github.com/karlkauc/JdbcOdata/\.github/workflows/release\.yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
sha256sum -c --ignore-missing SHA256SUMS

# image
cosign verify ghcr.io/karlkauc/jdbcodata:1.1.0 \
  --certificate-identity-regexp '^https://github.com/karlkauc/JdbcOdata/\.github/workflows/release\.yml@refs/tags/v' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

## Metadata database

| | H2 (default) | PostgreSQL |
|---|---|---|
| Use | Single instance | Production, several instances |
| Configuration | None | `jdbcodata.meta.url=jdbc:postgresql://host:5432/jdbcodata` and login |
| Backup | Stop the service, copy `data/*.mv.db` | `pg_dump jdbcodata` |

To move from H2 to PostgreSQL, recreate datasources and queries in the new instance through a seed file or the REST API. The cache does not need to be migrated.

## Backup and restore

Back up the metadata database, `config/`, `certs/` and the master key. Without the master key, stored database passwords are lost and must be entered again.
Cache and execution log are expendable.

!!! warning
    Store the master key separately from the database backup. By default `data/master.key` sits next to the H2 file, so a backup of `data/` contains both the
    encrypted passwords and the key. Prefer `JDBCODATA_MASTER_KEY` from a secret store.

## Monitoring

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Liveness and readiness, no login |
| `GET /actuator/prometheus` | Metrics (`jdbcodata_executions_total`, `jdbcodata_execution_duration_seconds`, pools, limiters, JVM, HikariCP of the metadata database). Basic Auth with an admin account, or `jdbcodata.security.metrics-token`, set in Prometheus as `authorization: { credentials: … }` |
| `jdbcodata_pool_connections_pending` | Staying above 0 means the datasource's `poolSize` is too small. Also available: `jdbcodata_pools`, `jdbcodata_pool_connections_active`, `jdbcodata_executions_running`, `jdbcodata_ratelimit_rejected_total`, `jdbcodata_executionlog_dropped_total`. Cache hits are in the `cache` label of `jdbcodata_executions_total` |
| Admin UI → Statistics | Executions, error rate, cache hit rate, p50/p95 |
| Admin UI → Settings | Master key source, pools, dropped log records |

Logs go to stdout, or to a file with `logging.file.name`. Each request has an ID, which appears in its log lines and in the `X-Request-Id` response header. An ID sent by your proxy is kept if it has at most 64 characters from `A-Za-z0-9._-`. For JSON logs, set `logging.structured.format.console=ecs`. The ID is then in the `requestId` field. Spring Boot also offers `logstash` and `gelf`. Errors during query execution carry a reference such as `ref 1a2b3c4d`, which the client gets too.

Datasources with a technical account are checked every minute (`jdbcodata.health.interval-ms`, `0` turns it off). Others show the result of the last execution that
reached the database.

## Operating it safely

- Give database accounts read rights only. Not every driver enforces the read-only flag.
- Do not let superuser or write accounts sign in over the network. See [pass-through](identity.md#pass-through).
- Keep admin accounts to trusted people, and restrict a public admin UI to known networks.
- `./mvnw -Psecurity verify` checks dependencies for known CVEs and stops at CVSS 7. `NVD_API_KEY` speeds up the download.

See [Security](security.md) for the threat model and known limits.

## Single sign-on for the admin UI

The admin UI can sign in with OpenID Connect, tested with Keycloak. Feeds keep using Basic Auth. The provider is a
standard Spring registration, and roles come from a claim:

```yaml
spring.security.oauth2.client:
  registration.corp:
    client-id: jdbcodata
    client-secret: ${OIDC_CLIENT_SECRET}
    client-name: Company account      # button label
    scope: openid,profile
  provider.corp:
    issuer-uri: https://login.example.org/realms/corp
jdbcodata.security.oidc:
  role-claim: groups
  admin-values: [jdbcodata-admins]
  viewer-values: [jdbcodata-viewers]
```

Register `https://<host>/login/oauth2/code/corp` as redirect URI and `https://<host>/login?logout` as post-logout URI
at the provider. Behind a proxy, set `server.forward-headers-strategy=native`, or the redirect URI will be wrong.

- **Roles:** Values match exactly. A user in neither `admin-values` nor `viewer-values` cannot sign in, which the audit
  log records as `LOGIN_FAILED`. A user in both becomes ADMIN.
- **Revoking access:** The role is read at sign-in only. A running session keeps it until it ends (default: 30 minutes
  idle, `server.servlet.session.timeout`).
- **Entra ID:** With more than 200 groups, Entra leaves out the `groups` claim. Use app roles with `role-claim: roles`
  there. `username-claim` can point to `email` or another claim.
- **Startup:** With `issuer-uri`, the provider must be reachable at startup, or the feeds will not start either. To avoid
  that, set `authorization-uri`, `token-uri`, `jwk-set-uri`, `user-info-uri` and `user-name-attribute` instead. Without
  discovery, signing out no longer ends the provider session.
- **SSO only:** `jdbcodata.security.oidc.local-login=false` removes password logins. This includes the REST API, which then
  answers 401 because Basic Auth is all it supports so far. The service refuses to start in this mode without a
  registration or `admin-values`. For emergency access, set the option back to `true` and restart.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Start stops with "Refusing to start without TLS" | Configure `server.ssl.*`, or set `jdbcodata.security.allow-plain-http=true` behind a proxy |
| Start stops with "must have at least 8 characters" | Set a longer `jdbcodata.admin.password`, or leave it empty for a generated one |
| Excel keeps asking for credentials | The login was rejected (401). Look for `AUTH_FAILED` in Statistics |
| With a login, the browser shows someone else's data | Anonymous access is on, and browsers send no credentials without a 401. Send the login directly |
| Query missing in the Navigator | Disabled, no schema snapshot, or FIXED without a grant for the caller |
| 503 "busy" | Raise the datasource's parallel executions, or enable the cache |
| Slow responses or connection timeouts with many callers of one technical account | They share its pool, so raise the datasource's connections per login. The limit applies per login and instance, and the database must allow that many connections |
| 503 "Database temporarily unavailable" | No datasource was reachable to check the login |
| 413 | Result above the row limit. Use parameters or `$top`, or raise the limit |
| 429 | Too many failed logins. Wait a minute |
| "No JDBC driver for the URL" | Put the driver JAR into `lib/`, see [Database drivers](drivers.md) |
| "JDBC URL not allowed" | Add the prefix to `jdbcodata.security.allowed-jdbc-urls` |
| "encrypted with key … but the configured master key is …" | The master key changed. Restore it, or enter the credentials again |
