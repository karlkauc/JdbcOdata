# Database drivers

| Database | URL | Driver | Push-down | Session settings |
|---|---|---|---|---|
| PostgreSQL | `jdbc:postgresql://host:5432/db` | Included | Yes | Yes |
| MariaDB, MySQL | `jdbc:mariadb://host:3306/db` | Included | Yes | Yes |
| Oracle | `jdbc:oracle:thin:@//host:1521/SERVICE` | [Add it yourself](#oracle) | Yes | Yes |
| Others | Depends on the driver | Add it to `lib/` | No | No |

Use the `jdbc:mariadb:` URL for MySQL as well: the included MariaDB driver talks to MySQL. `jdbc:mysql:` would need MySQL Connector/J in `lib/` and an allowed prefix.

MySQL 8 and later log in with `caching_sha2_password` by default. Use TLS, or add `allowPublicKeyRetrieval=true` to the URL on a network you trust. Without either, the connection fails with `RSA public key is not available client side`.

!!! note "How well each database is tested"
    The automated tests run against H2 and real PostgreSQL, MariaDB 11.4 and MySQL 8.4 servers. Oracle has no automated test against a real server yet, so check your queries before you rely
    on them, and please report problems.

## Adding a driver

JAR files in `lib/` are put on the class path at start.

=== "Distribution ZIP"

    Copy the JAR to `lib/` next to `jdbcodata.jar`. The start scripts set `-Dloader.path=lib`.

=== "Docker"

    Put the JAR into `docker/lib/`, which Compose mounts at `/app/lib`, and restart. Without Compose, mount a volume at `/app/lib`.

=== "Plain JAR"

    ```bash
    java -Dloader.path=lib -jar jdbcodata.jar
    ```

## Oracle

The Oracle driver is **not included**, because it is proprietary (Oracle Free Use Terms) and JdbcOdata is MIT licensed. The URL prefix `jdbc:oracle:thin:` is already allowed.

1. Get `ojdbc11.jar` from Oracle or Maven Central.
2. Put it into `lib/` as described above.
3. Restart and create the datasource.

If you build from source for your own use, you can compile it in instead:

```bash
./mvnw -Poracle clean package
```

Mind Oracle's terms if you pass such a build on. Without a driver, **Test connection** reports `No JDBC driver for the URL`.

## SQL Server and others

Add the driver to `lib/`, then allow its prefix:

```yaml
jdbcodata:
  security:
    allowed-jdbc-urls:
      - "jdbc:sqlserver:"
```

These databases run plain queries. Push-down and session settings are available only for the databases in the table above.

## H2

H2 is included because the demo and the default metadata database use it. It is deliberately hard to use as a datasource.

- **Embedded H2** (`jdbc:h2:mem:`, `jdbc:h2:file:`) runs inside the service and could read its files. It needs `jdbcodata.security.allow-embedded-h2: true`, which is meant for the demo and tests.
- **H2 over the network** (`jdbc:h2:tcp:`, `jdbc:h2:ssl:`) is not allowed by default, because an H2 server can send serialized Java objects to the service. Only add these prefixes to
  `allowed-jdbc-urls` for a server you trust.

## What JDBC URLs may not contain

A JDBC URL can do more than connect, so these are rejected, in the URL and in driver properties:

- Passwords, including key store and TLS key passwords. Use the login fields.
- Options that load classes or files, or that run SQL on connect, such as `INIT`, `socketFactory`, `sslfactory`, `initSql`, `sessionVariables` or `allowLocalInfile`.
- Options that switch off read-only mode, such as `readOnlyMode` or PostgreSQL's `options`. Use [session settings](admin-guide.md#session-settings) instead.
- Multi-statement options such as `allowMultiQueries`.

The list covers known options of the included drivers. If you add other drivers, an administrator is trusted with their options.
