# FAQ

## Can clients change data?

No. Feeds are read-only: connections are read-only, SQL is restricted to `SELECT`, and change sets in `$batch` are rejected.

## Does JdbcOdata store my database password?

Not with pass-through. The password is used to open the connection and is kept in memory while the pool for that login is alive. It is not written to disk or logged. Service users and technical accounts do store a database
password, encrypted with AES-256-GCM.

## Why do I need TLS?

Clients use HTTP Basic, which sends the password with every request. The service therefore refuses to start without TLS, unless you explicitly allow plain HTTP for use behind a TLS proxy.

## Is SSO supported?

No. There is no Kerberos, OAuth or OpenID Connect, because Excel's OData connector with a database login is the main use case. Use service users where personal database accounts do not fit.

## Why is the Oracle driver missing?

Its licence is proprietary and does not fit an MIT release. Putting it into `lib/` takes one step; see [Oracle](drivers.md#oracle).

## Does it work with SQL Server?

Plain queries should work with the driver in `lib/` and `jdbc:sqlserver:` in `allowed-jdbc-urls`. Push-down and session settings are not available, and SQL Server is not part of the automated tests.

## My result has more than 100,000 rows

Above `jdbcodata.odata.max-rows` the service answers 413. Narrow the query with parameters or filters, or raise the limit together with the heap, because results are held in memory. The limit on datasource and
query must be raised as well if they are set lower. For large results that clients rarely load in full, enable [push-down](admin-guide.md#4-push-down).

## Should I enable server-driven paging?

Only together with the result cache. Without it, every page runs the query again and data can change between pages. See [Paging](odata-reference.md#paging).

## Excel always shows old data

Check whether the query has a result cache with a long TTL, and look for `X-Cache: HIT`. Append `refresh=true` or invalidate the entry under **Cache**.

## My query does not show up in Excel

The query needs to be enabled and have a schema snapshot. FIXED queries also need a grant for the caller. Also check that your login is valid on the query's datasource.

## Every login shows the same data

If anonymous access is on, a client that sends no credentials stays anonymous and gets the anonymous user's data. Compare `caller` and `effectiveDbUser` in the execution log, or under **Statistics**.

## What does the live demo run on?

The public demo runs the same software with a sample PostgreSQL database. Anonymous access is enabled for it. The example deployment is in `deploy/` in the repository.

## How does it relate to the OData Online Viewer?

They are independent sister projects. JdbcOdata publishes feeds, and the [viewer](https://www.odata-viewer.online) explores any OData service, including these feeds.
