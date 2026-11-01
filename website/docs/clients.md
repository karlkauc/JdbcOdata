# Connect clients

Every client needs the feed URL and a login. The service URL is shown on the **Overview** page of the admin UI.

| Login | User name | Password |
|---|---|---|
| Pass-through | your database user | your database password |
| Service user | the service user, for example `svc_reporting` | the service password |

See the [identity model](identity.md) for which to use.

## Excel (Microsoft 365)

1. **Data → Get Data → From Other Sources → From OData Feed**.
2. Enter `https://<host>/odata/` for the list of all feeds, or one query directly, for example
   `https://<host>/odata/Sales?p_year=2025`.
3. Choose **Basic** and enter user name and password. Excel stores them per URL under **Data source settings**.
4. Pick the query in the Navigator, then **Load** or **Transform Data**.
5. Refresh with **Data → Refresh All**. Parameters are part of the URL; to change one, open the query in the Power Query
   editor and edit the source.

!!! tip "Good to know"
    - Append `?refresh=true` (or `&refresh=true`) to bypass the cache.
    - Filters in Power Query are sent as `$filter`.
    - With a self-signed certificate, install the CA on the client. Otherwise Excel reports a connection error.
    - Date and time values arrive as `DateTimeOffset` in UTC. Excel converts them to local time.

## Power BI Desktop

1. **Get Data → OData feed**, same URL as above, sign in with **Basic**.
2. Pick a table and load it. Query folding passes `$filter`, `$select` and `$top` to the service.

Relationships declared between queries appear as navigation properties. The OData connector shows them as extra columns
and loads them with `$expand` when you expand a column. For large models it is usually better to load tables separately
and relate them in Power BI through their key columns.

## Power BI Service with a gateway

1. Publish the dataset and create an OData data source in the gateway with **Basic** authentication.
2. Use only a **service user** with stored database credentials here, never a personal database account: the gateway
   stores the password.
3. Match the scheduled refresh to the query's cache TTL.

Reports that rely on row-level security for each person belong in Power BI Desktop with a personal login.

## OData Online Viewer

The [OData Online Viewer](https://www.odata-viewer.online) loads a service by URL and supports HTTP Basic. It shows the
model as a tree and diagram, and lets you try queries and copy the request URL. The service must be reachable from the
viewer, so an internal host will not work with the public site.

## curl and scripts

```bash
curl -u report_ro:secret 'https://<host>/odata/Sales?p_year=2025&$filter=region%20eq%20%27Nord%27&$top=10'
```

Send the password right away. If a service has anonymous access enabled, clients that only send credentials after a
401, such as browsers, stay anonymous and see the anonymous user's data instead of their own.

With an [API key](admin-guide.md#8-api-keys), send it as Bearer token:

```bash
curl -H 'Authorization: Bearer jdo_…' 'https://<host>/odata/Sales?$top=10'
```

In Excel and Power BI, choose *Basic*, enter `apikey` as user and the key as password.

## Error messages

| Message | Meaning |
|---|---|
| 401, password prompt again | The database or the service rejected the login |
| Query missing in the Navigator | Disabled, no schema snapshot, or a FIXED query without a grant for you |
| 400 "Missing required parameter" | Add the parameter to the URL, for example `?p_year=2025` |
| 413 | The result is larger than the row limit. Use parameters or a filter, or ask the admin to raise the limit |
| 429 | Too many failed logins. Wait a minute and check the password |
| 503 "busy" | The datasource is at its limit of parallel executions. Try again shortly |
| 503 "Database temporarily unavailable" | The login could not be checked because no database was reachable |
