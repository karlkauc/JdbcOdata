# Security

## Reporting a vulnerability

Please do not open a public issue. Report it privately through
[GitHub security advisories](https://github.com/karlkauc/JdbcOdata/security/advisories/new).

## Secure defaults

| Topic | Default |
|---|---|
| TLS | The service refuses to start without TLS unless you explicitly allow plain HTTP |
| Admin password | Random, in a file with mode 600, never logged. Short or placeholder passwords stop the start |
| Anonymous access | Off. Requests without Basic credentials get 401 |
| Metadata | `$metadata` needs a valid login, also during a database outage |
| Docker | Only the TLS proxy is published. The demo stack is bound to `127.0.0.1` |
| Demo profile | Off, and bound to `127.0.0.1` when on |
| Embedded and networked H2 | Not allowed as a datasource |

## Who may do what

| Actor | May | May not |
|---|---|---|
| Anonymous client, only with `anonymous-user` set | Read what this service user may read, and see the schema of CALLER queries in `$metadata` | See FIXED queries without a grant, read other queries, guess logins without limit |
| Pass-through caller | Use queries of datasources that accept the login. The database decides on rows | See `$metadata` without a valid login, or results of other callers |
| Service user | Use stored credentials and granted FIXED queries | Read stored passwords |
| `VIEWER` | Read configuration, statistics, cache, audit | Change anything, see passwords or sensitive parameter values |
| `ADMIN` | Manage datasources, queries and accounts | Run code on the server or read the service's files. This is the goal; see known limits |

The model assumes:

- TLS ends at the service or at a proxy that drops client `X-Forwarded-*` headers.
- Database accounts behind datasources have read rights only.
- Superuser accounts of the datasources cannot sign in over the network.

## How it is protected

- **SQL injection.** OData options are never concatenated into SQL. Values are bound and identifiers come only from the schema snapshot. Query parameters are always bound.
- **Read-only.** Connections are read-only and SQL is restricted to `SELECT`: no writing CTEs, no `SELECT INTO`, no `FOR UPDATE`, no executable MySQL or MariaDB comments.
- **JDBC URLs.** Only known network drivers are allowed, and options that load code, run SQL or weaken read-only mode are rejected.
- **Session settings.** Only known keys per database, and values without quotes, semicolons or backslashes. The service builds the statement.
- **Login throttling.** Failed logins are counted per IP and per user name, and then answered with 429.
- **Secrets.** Stored database passwords use AES-256-GCM, tied to their record. Admin and service user passwords use bcrypt. Pass-through passwords are never stored.
- **Admin UI.** `HttpOnly`, `SameSite=Lax` and `Secure` session cookies, CSRF protection, a Content Security Policy without inline scripts. Sessions end when an account is disabled or
  its password or role changes.
- **Logs.** Sensitive parameters appear as `***` in the execution log, CSV export and cache overview. CSV cells are protected against formula injection.
- **Resources.** Row limits, timeouts, parallel execution limits per datasource, a 10 MB request body limit and a `$batch` limit.
- **Base images.** Pinned by digest.

## Usage statistics

JdbcOdata does not phone home. Unless you set `jdbcodata.usage.db-url`, it records nothing, starts no thread for it and
opens no connection. There is no built-in reporting address, so with that setting the data goes to a database you
choose.

The public demo at `odata.status20.net` uses this setting. One row per OData request holds:

- the kind of request, the HTTP status and the duration
- the name of the published query, or `other`
- the names of the query options, such as `$filter` and `$top`
- the kind of client and its User-Agent header
- whether credentials were sent
- a visitor hash that changes daily
- the version of JdbcOdata

It never holds SQL, filter or parameter values, result data, user names, passwords, the IP address or the query string.
There are no cookies and no tracking script. The hash is `sha256(daily salt | IP | User-Agent)`, so a visitor counts once
per day and cannot be followed from one day to the next.

## Known limits

These are the things to plan for. Please read them before exposing the service to the internet.

### Pass-through triggers real database logins

Every Basic login that is not a service user is tried as a database login, and a failed login is tried on every datasource. Rate limiting allows 20 failures per IP and 100 per user name per
minute. If your database locks accounts after failed logins, **an outsider who can reach the service can lock those accounts**. Put the service behind a VPN or an allowlist, or use dedicated
reporting accounts without a lockout policy.

### The master key sits next to the data by default

Without `JDBCODATA_MASTER_KEY`, `data/master.key` lies next to the H2 metadata database, so a copy of `data/` contains both ciphertext and key. Set the key from a secret store and keep backups
separate.

### Cached results are not encrypted

Result cache rows are stored compressed, but **not encrypted**, in the metadata database. Anyone with access to it can read cached data. Leave the cache off for sensitive queries, or protect the
metadata database accordingly.

### Further points

- `UMask=0077` is set only in the systemd unit. With other start methods, files in `data/` and logs follow the user's umask.
- Results are held in memory, so large `max-rows` values and many parallel calls can exhaust the heap. Size them together.
- Signed-in callers see the database's error text, which helps diagnosis. Anonymous callers get a reference number only.
- JDBC options are checked against a denylist of known dangerous options, not an allowlist. Treat `ADMIN` accounts as highly trusted.
- Admin login has no MFA and no forced password change. Restrict the admin UI to known networks where possible.
