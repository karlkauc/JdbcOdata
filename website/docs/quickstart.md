# Quickstart

Three ways to run JdbcOdata. Start with the demo: it needs nothing but Java and comes with sample data.

## Demo in 5 minutes

You need Java 21. Maven comes with the repository.

```bash
git clone https://github.com/karlkauc/JdbcOdata.git
cd JdbcOdata
./mvnw package -DskipTests
java -jar target/jdbcodata.jar --spring.profiles.active=demo
```

On Windows, use `mvnw.cmd` instead of `./mvnw`.

The `demo` profile creates an H2 sample database with a `sales` table under `./data/demo` and publishes it as the
query `Sales`. The service listens on `127.0.0.1` only, over plain HTTP.

The first start generates the admin password:

```bash
cat data/initial-admin-password
```

| What | URL | Login |
|---|---|---|
| Service document | <http://localhost:8080/odata/> | `demo` / `demo` |
| Metadata | <http://localhost:8080/odata/$metadata> | `demo` / `demo` |
| Data | <http://localhost:8080/odata/Sales?p_year=2025&$top=100> | `demo` / `demo` |
| Admin UI | <http://localhost:8080/admin> | `admin` / the generated password |
| Health | <http://localhost:8080/actuator/health> | none |

Try it from the command line:

```bash
curl -u demo:demo 'http://localhost:8080/odata/Sales?p_year=2025&$top=3'
```

Or open it in Excel: **Data → Get Data → From Other Sources → From OData Feed**, enter `http://localhost:8080/odata/`,
choose **Basic** and sign in as `demo` / `demo`.

!!! warning "The demo profile is for your own machine"
    It allows plain HTTP and an embedded database that runs inside the service. Do not use it for real data, and
    do not expose it to a network.

## Demo with Docker

```bash
cd docker
docker compose -f docker-compose.demo.yml up -d --build
docker exec jdbcodata-demo cat data/initial-admin-password
```

The port is published on `127.0.0.1:8080` only.

## Production-style stack with Docker Compose

This stack runs Caddy for TLS, JdbcOdata and PostgreSQL as the metadata database.

```bash
cd docker
cp .env.example .env
```

Set the values in `.env`. Compose refuses to start while the secrets are empty.

```bash
openssl rand -base64 24    # JDBCODATA_ADMIN_PASSWORD and POSTGRES_PASSWORD, one value each
openssl rand -base64 32    # JDBCODATA_MASTER_KEY
docker compose up -d --build
```

With `JDBCODATA_HOST=localhost` and `JDBCODATA_TLS=internal`, Caddy issues a self-signed certificate. For a public host,
see [Operations](operations.md#docker-compose).

## Native install

`./mvnw package` also creates `target/jdbcodata-<version>-dist.zip` with start scripts, sample configuration and service
templates. See [Operations](operations.md#native-install-linux-windows).

Without the demo profile, JdbcOdata refuses to start until TLS is configured or
`jdbcodata.security.allow-plain-http=true` is set for use behind a TLS proxy.

## Your first own feed

1. In the admin UI, open **Datasources → New datasource** and enter a JDBC URL.
2. Open **Queries → New query**, pick the datasource and write a `SELECT`. Parameters are written as `:name`.
3. Use **Test run** with your own database login.
4. **Save**, then **Take schema snapshot**. The query now appears in the feed.

The [admin guide](admin-guide.md) covers every step in detail.
