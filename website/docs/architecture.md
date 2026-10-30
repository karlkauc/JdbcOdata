# Architecture

JdbcOdata is a single Spring Boot process. It has no separate query engine or message broker. Its state lives in a metadata database.

```mermaid
flowchart LR
    subgraph Clients
        XL[Excel / Power Query]
        BI[Power BI]
        AD[Admin browser]
    end
    subgraph Service["JdbcOdata service"]
        OD["/odata (Apache Olingo)"]
        UI["/admin"]
        API["/api/v1"]
        ID[Identity resolver]
        QE[Query engine]
        CA[Result cache]
        ST[Execution log]
        DS[Datasources and pools]
    end
    META[("Metadata DB: H2 or PostgreSQL")]
    TARGET[("Your databases")]
    XL -- HTTPS + Basic --> OD
    BI -- HTTPS + Basic --> OD
    AD -- HTTPS + login --> UI
    OD --> ID --> QE
    UI --> QE
    API --> QE
    QE --> CA
    QE --> DS
    QE --> ST
    CA --> META
    ST --> META
    DS -- JDBC --> TARGET
```

## Stack

| Part | Choice |
|---|---|
| Runtime | Java 21 |
| Framework | Spring Boot 4, Spring Security |
| OData | Apache Olingo 5 |
| Database access | JDBC, HikariCP |
| SQL validation | JSqlParser |
| Metadata | H2 file or PostgreSQL, Flyway migrations |
| Admin UI | Thymeleaf, htmx, CodeMirror, no Node build |
| Cache | Caffeine in front of a table in the metadata database |

## A request from Excel

```mermaid
sequenceDiagram
    participant C as Excel
    participant O as OData endpoint
    participant I as Identity resolver
    participant K as Result cache
    participant D as Database
    participant L as Execution log
    C->>O: GET /odata/Sales?p_year=2025 (Basic)
    O->>I: Who runs this query?
    I-->>O: Database identity
    O->>K: Lookup by query, version, database user, parameters
    alt Cache hit
        K-->>O: Rows
    else Miss or refresh=true
        O->>D: Prepared statement (read-only, timeout, row limit)
        D-->>O: Rows
        O->>K: Store with TTL
    end
    O->>O: Apply OData options
    O->>L: Who, what, how long, rows, status
    O-->>C: 200 JSON, X-Cache header
```

Without push-down, OData options run in memory on the query result. With [push-down](admin-guide.md#4-push-down), they are wrapped around the SQL as far as the meaning stays the same.

The cache key contains the database user, not the password. For a pure pass-through caller, a cache hit therefore still checks the login first, so a wrong password gets 401 rather than cached data.

## Connections

Each identity gets a small HikariCP pool per datasource, identity and session settings, with two connections. Idle pools are closed after a few minutes, so refreshes hit a warm pool, but credentials do not stay open
for long. Every connection is read-only, with auto-commit off and a rollback at the end.

## The OData model

Each enabled query with a schema snapshot becomes an entity set. The snapshot records columns, types and keys, so the model does not depend on a live connection. Relations between queries become navigation properties, and
may span datasources. The feed is built per caller: `$metadata` shows only what this caller may see.

## Data at rest

The metadata database holds datasources, queries and versions, parameters, column snapshots, grants, service users, encrypted credentials, admin accounts, the result cache, the execution log and the audit log. The master key
is never stored there.

## Package layout

| Package | Responsibility |
|---|---|
| `odata` | Olingo servlet, dynamic model, option evaluation |
| `admin` | Admin UI and REST API, accounts, roles |
| `query` | Query definitions, SQL validation, snapshots, execution |
| `identity` | Identity resolution, service users, credentials |
| `datasource` | Datasources, pools, JDBC URL policy, session settings |
| `cache` | Result cache |
| `stats` | Execution log, statistics, retention |
| `security`, `crypto`, `config` | TLS guard, rate limiting, encryption, configuration |
