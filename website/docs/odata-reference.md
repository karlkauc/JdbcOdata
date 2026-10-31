# OData feature reference

JdbcOdata serves OData V4 in JSON and is read-only. Unless push-down is enabled for a query, options are evaluated in memory on the
result of the query. Examples use the entity sets `Sales` and `Customers`.

## URLs

| URL | Meaning |
|---|---|
| `/odata/` | Service document |
| `/odata/$metadata` | Metadata |
| `/odata/Sales` | Entity set, one per query |
| `/odata/Sales?p_year=2025` | Query parameters are plain URL parameters |
| `/odata/GetSales(p_year=2024)` | Parameters as a function, composable with key, navigation, `$count` and options |
| `/odata/Sales(1)` or `/odata/Customers/1` | Single entity |
| `/odata/Sales(1)/Customer` | Navigation path |
| `/odata/Customers(1)/Sales/$ref` | Entity references |
| `/odata/$all?$search=Vienna` | Search across feeds |
| `/odata/$crossjoin(Sales,Customers)?$filter=…` | Cross join |

## Query options

| Option | Notes |
|---|---|
| `$filter` | Comparison, logic, string, date and math functions, `any`/`all`, `in`, `cast`, `isof`, `$it`, `$root`, parameter aliases (`$filter=Name eq @n&@n='Vienna'`) |
| `$orderby`, `$top`, `$skip`, `$count`, `$select` | `$select` also takes nested options on collections |
| `$expand` | Follows relations, with nested options, up to three levels. `$levels` is supported |
| `$search` | Full-text search over the columns of the feed |
| `$compute` | `$compute=Amount mul 1.2 as Gross` |
| `$apply` | See below |
| `refresh=true` | Bypasses the cache and stores the fresh result |

```text
/odata/Sales?$filter=Customer/Region eq 'North'
/odata/Customers?$filter=Sales/any(s:s/Amount gt 10000)
/odata/Customers?$filter=Sales/$count gt 50
/odata/Sales?$select=Items($filter=Amount gt 100;$orderby=Amount desc;$top=3;$count=true)
```

## Aggregation with `$apply`

```text
/odata/Sales?$apply=groupby((Region),aggregate(Amount with sum as Total))&$orderby=Total desc
/odata/Sales?$apply=groupby((rollup(Region,Country)),aggregate($count as N))
```

Supported: `groupby` with `rollup`, `aggregate` with `from` and inline aggregates, `topcount` and similar, `join` and `outerjoin`,
`nest`, hierarchies (`ancestors`, `descendants`, `traverse`), the transformation `expand`, and the custom aggregation methods
`JdbcOdata.median`, `stddev` and `variance`.

## OData 4.01 notation

These forms are rewritten before parsing: `case(…)`, `$filter` as a path segment, `matchesPattern`,
`Sales/$count($filter=…)`, keys as path segments (`Customers/1`) and spaces after the lambda colon.

## Paging

Server-driven paging is off by default (`jdbcodata.odata.page-size: 0`). With a value set, responses end with `@odata.nextLink`,
and Excel and Power BI follow it automatically. Clients can ask for smaller pages with `Prefer: odata.maxpagesize=n`.

!!! warning "Enable the query cache when you use paging"
    Each page is a separate request. Without the result cache, the query runs again for every page, which adds load, and the data can
    change between two pages, so rows can be duplicated or missed. With the cache, later pages come from the same entry.

## Caching headers

| Header | Meaning |
|---|---|
| `ETag`, `If-None-Match` | Every read response has a weak ETag; a match gives 304 |
| `Cache-Control: private, no-cache` | Together with `Vary: Authorization`, because content is per caller |
| `X-Cache`, `X-Cache-Age` | Status of the result cache and age of the entry |
| `X-Push-Down` | What ran in the database, for example `filter`, `orderby`, `top` |

## `$batch`

`$batch` (multipart/mixed) runs read parts like single requests under the same identity. A batch has at most **100 requests**, and larger
ones get 400. Change sets are rejected with 405. Request bodies are limited to 10 MB.

## Delta links

Send `Prefer: odata.track-changes` and later follow `@odata.deltaLink` to get new, changed and deleted rows since the last call. The service
keeps a snapshot in the metadata database for 7 days, across restarts. After that the link answers 410 and the client loads everything again.
This needs key columns, and these requests bypass the result cache.

## Asynchronous requests

`Prefer: respond-async` returns 202 and a status monitor at `/odata/$async/<id>`. Results stay available for one hour. A caller can run up to
four jobs at the same time.

## Limits

| Limit | Default | Set by |
|---|---|---|
| Rows per result | 100,000, above that 413 | `jdbcodata.odata.max-rows`, datasource and query may only lower it |
| Timeout | 60 s | `jdbcodata.odata.default-timeout-sec`, datasource, query |
| Parallel executions per datasource | 10, above that 503 with `Retry-After` | Datasource |
| Request body | 10 MB, above that 413 | Fixed |
| `$batch` | 100 requests | Fixed |
| `$expand` | 3 levels, 200,000 expanded rows | Fixed |
