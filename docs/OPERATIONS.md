# Betriebshandbuch

## Voraussetzungen

- Java 21 (nativ) oder Docker 24+ mit Compose
- Netzwerkzugriff vom Service zu den Ziel-Datenbanken
- Ein Host-Name und ein Zertifikat für TLS (Basic Auth sendet Passwörter, HTTP ohne TLS ist nur lokal zulässig)

## Native Installation (Linux, Windows)

1. `jdbcodata-1.0.0-dist.zip` entpacken (aus `./mvnw package`, Datei `target/jdbcodata-1.0.0-dist.zip`).
2. `config/application-example.yml` nach `config/application.yml` kopieren und anpassen: Port, TLS-Keystore,
   Metadaten-DB, Admin-Passwort, Master-Key (`openssl rand -base64 32`).
3. Starten: `bin/jdbcodata.sh` (Linux) oder `bin\jdbcodata.cmd` (Windows). Erster Start legt `data/` an,
   erzeugt die Metadaten-DB (H2) und das Admin-Konto.
4. `https://host:8443/admin` öffnen, anmelden, Datenquelle und erste Query anlegen (siehe ADMIN_GUIDE.md).

Als Dienst: `contrib/jdbcodata.service` (systemd) bzw. `contrib/jdbcodata-winsw.xml` (Windows-Dienst via WinSW).
Secrets gehören in `/etc/jdbcodata/jdbcodata.env` (Vorlage `contrib/jdbcodata.env.example`, chmod 600).

### TLS eingebaut

```bash
# selbstsigniert für Tests; produktiv ein Zertifikat der Firmen-CA als PKCS12 exportieren
export SERVER_SSL_KEY_STORE_PASSWORD="$(openssl rand -base64 24)"   # in /etc/jdbcodata/jdbcodata.env übernehmen
keytool -genkeypair -alias jdbcodata -keyalg RSA -keysize 2048 -validity 365 \
  -dname "CN=odata.example.org" -keystore certs/jdbcodata.p12 -storetype PKCS12 -storepass "$SERVER_SSL_KEY_STORE_PASSWORD"
```

```yaml
server:
  port: 8443
  ssl:
    key-store: certs/jdbcodata.p12
    key-store-type: PKCS12        # Passwort aus SERVER_SSL_KEY_STORE_PASSWORD, nicht in die Datei
```

Ohne `server.ssl.*` startet der Service nur mit `jdbcodata.security.allow-plain-http: true` und warnt im Log.

## Docker Compose (PostgreSQL + Caddy)

```bash
cd docker
cp .env.example .env            # Host, Admin-Passwort, Master-Key, PostgreSQL-Passwort setzen
docker compose up -d --build
docker compose logs -f jdbcodata
```

- `caddy` terminiert TLS auf 443. `JDBCODATA_HOST=localhost` und `JDBCODATA_TLS=internal` ergeben ein
  selbstsigniertes Zertifikat (Caddy-CA, für Excel am Client importieren). Für einen öffentlichen Host eine
  E-Mail-Adresse in `JDBCODATA_TLS` setzen (Let's Encrypt) oder ein eigenes Zertifikat einbinden
  (`JDBCODATA_TLS="/certs/cert.pem /certs/key.pem"` und Volume nach `/certs`).
- `jdbcodata` nutzt PostgreSQL als Metadaten-DB (`JDBCODATA_META_*`), spricht intern Klartext-HTTP
  (`JDBCODATA_SECURITY_ALLOW_PLAIN_HTTP=true`) und wertet `X-Forwarded-*` aus.
- Volumes: `jdbcodata-data` (Key-Datei, Logs), `postgres-data`, `caddy-data`. `./config` und `./lib` werden
  read-only eingebunden: `config/application.yml` für weitere Einstellungen (z.B. `jdbcodata.seed.file:
  /app/config/seed.yml`), `lib/` für zusätzliche JDBC-Treiber. Die Host-Ports des Proxys setzen
  `JDBCODATA_HTTP_PORT` und `JDBCODATA_HTTPS_PORT`.
- Demo ohne TLS und PostgreSQL: `docker compose -f docker-compose.demo.yml up -d` (Port 8080).

Update: `JDBCODATA_VERSION` in `.env` erhöhen, `docker compose up -d --build`.
Flyway migriert das Schema beim Start.

## Apache als Reverse Proxy

Statt Caddy kann ein vorhandener Apache (oder nginx, Traefik, IIS) TLS terminieren. Der Service läuft dann mit
`JDBCODATA_SECURITY_ALLOW_PLAIN_HTTP=true` und `SERVER_FORWARD_HEADERS_STRATEGY=native` nur auf
`127.0.0.1`, der Proxy setzt `X-Forwarded-Proto`, `X-Forwarded-Port` und `X-Forwarded-For`. Vom Client
mitgeschickte `X-Forwarded-*`-Header muss der Proxy verwerfen (Apache: `RequestHeader unset X-Forwarded-Host early`
usw., siehe Beispiel-vHost), sonst landen fremde Hosts in `@odata.context` und `@odata.nextLink`. Ein komplettes
Beispiel mit Docker-Stack, Let's-Encrypt-Zertifikat und Demo-PostgreSQL liegt unter
`deploy/odata.status20.net/` (vHost in `apache/odata.status20.net.conf`).

Für OData-URLs sind zwei Apache-Einstellungen Pflicht, sonst zerlegt der Proxy `$filter`, Klammern und
prozentkodierte Zeichen:

```apache
AllowEncodedSlashes NoDecode
ProxyPreserveHost On
ProxyPass        /actuator/ !
ProxyPass        / http://127.0.0.1:18085/ nocanon disablereuse=On
ProxyPassReverse / http://127.0.0.1:18085/
RequestHeader set X-Forwarded-Proto "https"
RequestHeader set X-Forwarded-Port  "443"
```

`/actuator/` bleibt intern; `/actuator/health` nutzt nur der Docker-Healthcheck. Der Hostname darf `odata`
enthalten (z. B. `odata.example.org`): der Service reicht Olingo den Pfad so weiter, dass die Suche nach dem
Servlet-Pfad im Hostnamen keine Rolle spielt.

## Metadaten-DB

| | H2 (Default) | PostgreSQL |
|---|---|---|
| Einsatz | Einzelinstanz, lokal | Produktion, mehrere Instanzen |
| Konfiguration | nichts | `jdbcodata.meta.url=jdbc:postgresql://host:5432/jdbcodata` + Login |
| Backup | Service stoppen, `data/*.mv.db` kopieren | `pg_dump jdbcodata` |

Wechsel H2 → PostgreSQL: Datenquellen und Queries per Seed-Datei oder REST-API (`GET /api/v1/queries`)
exportieren und in die neue Instanz einspielen. Der Cache muss nicht übernommen werden.

## Backup und Wiederherstellung

Sichern: Metadaten-DB, `config/`, `certs/` und `data/master.key` (ohne den Master-Key sind hinterlegte
DB-Passwörter verloren; dann müssen sie neu hinterlegt werden). Der Cache und das Ausführungsprotokoll sind
verzichtbar.

## Monitoring

| Endpunkt | Zweck |
|---|---|
| `GET /actuator/health` | Liveness/Readiness, ohne Login |
| `GET /actuator/prometheus` | Micrometer-Metriken (`jdbcodata_executions_total`, `jdbcodata_execution_duration_seconds`, Pools, Limiter, JVM, HikariCP der Metadaten-DB). Basic Auth mit Admin-Konto oder `jdbcodata.security.metrics-token`, in Prometheus als `authorization: { credentials: … }` |
| `jdbcodata_pool_connections_pending` | Dauerhaft über 0: `poolSize` der Datenquelle ist zu klein. Weitere Werte: `jdbcodata_pools`, `jdbcodata_pool_connections_active`, `jdbcodata_executions_running`, `jdbcodata_ratelimit_rejected_total`, `jdbcodata_executionlog_dropped_total`. Cache-Treffer stehen im Label `cache` von `jdbcodata_executions_total` |
| Admin-UI → Statistik | Ausführungen, Fehlerquote, Cache-Trefferquote, p50/p95 |
| Admin-UI → Einstellungen | Master-Key-Quelle, Pools, verworfene Protokollsätze |

Logs: stdout (Docker) oder `logs/jdbcodata.log` (`logging.file.name`). Jede Anfrage trägt eine Request-ID, die in den
Log-Zeilen und als `X-Request-Id` in der Antwort steht; eine ID vom Proxy wird übernommen, wenn sie aus höchstens 64
Zeichen `A-Za-z0-9._-` besteht. Für JSON-Logs (z. B. für Elastic oder Loki) `logging.structured.format.console=ecs`
setzen, die Request-ID steht dann im Feld `requestId`. Spring Boot bietet außerdem `logstash` und `gelf`. Fehler bei Query-Ausführungen tragen
eine Referenz (`ref 1a2b3c4d`), die auch der Client erhält.

## Sicherheit im Betrieb

- Basic Auth nur über TLS; HSTS wird vom Caddy gesetzt.
- Fehlgeschlagene Logins werden pro Client-IP und Benutzername gezählt (`jdbcodata.security.rate-limit-per-minute`),
  danach antwortet der Service mit 429. Hinter einem Proxy nur mit `server.forward-headers-strategy=native` (siehe
  oben), sonst ist die IP fälschbar.
- Die Admin-UI sendet eine Content-Security-Policy (nur eigene Skripte, kein Framing); Inline-Skripte und
  `on…`-Handler sind deshalb in Templates nicht erlaubt (`data-confirm`, `data-autosubmit` in `admin.js`).
- Ein ADMIN-Konto ist mächtig: Datenquellen-URLs sind auf bekannte Netzwerk-Treiber beschränkt
  (`jdbcodata.security.allowed-jdbc-urls`), trotzdem gehören Admin-Konten nur vertrauenswürdigen Personen.
  Öffentlich erreichbare Admin-UIs per Proxy auf bekannte Netze beschränken, wo möglich.
- Hinterlegte DB-Passwörter sind AES-256-GCM-verschlüsselt (`JDBCODATA_MASTER_KEY`), Admin- und
  Service-User-Passwörter bcrypt-gehasht. Pass-Through-Passwörter werden nicht gespeichert.
- Alle DB-Verbindungen sind read-only, SQL ist auf `SELECT` beschränkt (keine schreibenden CTEs, kein `SELECT
  INTO`, kein `FOR UPDATE`, keine MySQL-`/*! */`-Kommentare), Parameter werden gebunden. Read-only setzen nicht
  alle Treiber durch: Datenbank-Konten mit minimalen Rechten (nur `SELECT`) verwenden.
- Pass-Through: Jede Basic-Auth-Anmeldung, die kein Service-User ist, wird als DB-Login versucht. Konten mit
  Superuser- oder Schreibrechten dürfen sich deshalb nicht über das Netz an den Datenquellen anmelden können
  (z. B. `pg_hba.conf`: `host all <superuser> all reject`). Service-Dokument und `$metadata` gibt es erst nach
  erfolgreichem DB-Login; sichtbar sind nur Queries der Datenquellen, die den Login akzeptieren.
- OData-Request-Bodies (`$batch`, asynchrone Anfragen) sind auf 10 MB begrenzt (413).
- Sensible Query-Parameter erscheinen in Ausführungsprotokoll, CSV-Export und Cache-Übersicht als `***`.
- `./mvnw -Psecurity verify` prüft die Abhängigkeiten gegen bekannte CVEs (OWASP Dependency-Check, bricht ab
  CVSS 7 ab; `NVD_API_KEY` beschleunigt den Download).
- Pro Datenquelle gelten Timeout, Zeilenlimit und maximale parallele Ausführungen; darüber 503 mit `Retry-After`.
- Konfigurationsänderungen, Logins und Query-Testläufe stehen im Audit-Log (Admin-UI → Audit, `GET /api/v1/audit`).
  Bei abgelehnten Logins steht der eingegebene Name im Log, auch wenn jemand dort versehentlich ein Passwort eintippt.
- `jdbcodata.odata.anonymous-user` öffnet den Feed ohne Login unter einem Service-User. Nur für öffentliche
  Demo-Daten verwenden: der Service-User sieht alle Queries, für die er selbst (oder seine Gruppe) freigegeben ist
  – Freigaben für „alle Authentifizierten“ gelten für anonyme Aufrufer nicht –, und läuft mit seinen
  hinterlegten DB-Credentials; Rate-Limit und Datenquellen-Limits gelten weiterhin, eine Login-Sperre greift
  naturgemäß nicht.
  Clients, die Zugangsdaten nur auf eine 401-Aufforderung schicken (Browser), bleiben dann anonym und sehen die
  Daten des Service-Users statt ihrer eigenen.

## Fehlersuche

| Symptom | Ursache / Abhilfe |
|---|---|
| Start bricht ab: „Refusing to start without TLS“ | `server.ssl.*` konfigurieren oder `jdbcodata.security.allow-plain-http=true` hinter einem Proxy |
| Excel fragt immer wieder nach Zugangsdaten | DB lehnt Login ab (401). In Admin-UI → Statistik den Status `AUTH_FAILED` prüfen; bei Service-Usern Passwort/hinterlegte Credentials prüfen |
| Browser zeigt mit Login fremde Daten (z. B. alle statt einer Region) | Anonymer Zugang aktiv: ohne 401 schickt der Browser keine Zugangsdaten. Login direkt mitsenden (`curl -u`, Excel „Standard“) |
| Query fehlt im Excel-Navigator | Query inaktiv, ohne Schema-Snapshot, oder FIXED ohne Freigabe für den Aufrufer |
| Oracle meldet kurz nach einem Ladejob `ORA-01466` | Nach DDL auf einer Tabelle scheitern Read-only-Transaktionen für einige Sekunden. Erneut abrufen |
| 503 „busy“ | `maxConcurrent` der Datenquelle erhöhen oder Cache aktivieren |
| Langsame Antworten oder Verbindungs-Timeouts bei vielen Aufrufern eines technischen Accounts | Alle teilen sich dessen Pool: `poolSize` der Datenquelle erhöhen. Das Limit gilt pro Login und Instanz, die Datenbank muss so viele Verbindungen zulassen |
| 413 | Ergebnis über Zeilenlimit; Parameter oder `$top` nutzen, Limit erhöhen |
| 429 | Zu viele Fehlversuche; eine Minute warten, Passwort prüfen |
| „encrypted with key … but the configured master key is …“ | Master-Key gewechselt; alten Key wieder einsetzen oder Credentials neu hinterlegen |
