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
keytool -genkeypair -alias jdbcodata -keyalg RSA -keysize 2048 -validity 365 \
  -dname "CN=odata.firma.local" -keystore certs/jdbcodata.p12 -storetype PKCS12 -storepass change-me
```

```yaml
server:
  port: 8443
  ssl:
    key-store: certs/jdbcodata.p12
    key-store-password: change-me
    key-store-type: PKCS12
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

Update: `JDBCODATA_VERSION` in `.env` erhöhen, `docker compose pull && docker compose up -d --build`.
Flyway migriert das Schema beim Start.

## Apache als Reverse Proxy

Statt Caddy kann ein vorhandener Apache (oder nginx, Traefik, IIS) TLS terminieren. Der Service läuft dann mit
`JDBCODATA_SECURITY_ALLOW_PLAIN_HTTP=true` und `SERVER_FORWARD_HEADERS_STRATEGY=framework` nur auf
`127.0.0.1`, der Proxy setzt `X-Forwarded-Proto`, `X-Forwarded-Port` und `X-Forwarded-For`. Ein komplettes
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
enthalten (z. B. `odata.firma.at`): der Service reicht Olingo den Pfad so weiter, dass die Suche nach dem
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
| `GET /actuator/prometheus` | Micrometer-Metriken (`jdbcodata_executions_total`, `jdbcodata_execution_duration_seconds`, JVM, HikariCP), Basic Auth mit Admin-Konto |
| Admin-UI → Statistik | Ausführungen, Fehlerquote, Cache-Trefferquote, p50/p95 |
| Admin-UI → Einstellungen | Master-Key-Quelle, Pools, verworfene Protokollsätze |

Logs: stdout (Docker) oder `logs/jdbcodata.log` (`logging.file.name`). Fehler bei Query-Ausführungen tragen
eine Referenz (`ref 1a2b3c4d`), die auch der Client erhält.

## Sicherheit im Betrieb

- Basic Auth nur über TLS; HSTS wird vom Caddy gesetzt.
- Fehlgeschlagene Logins werden pro IP und Benutzername gezählt (`jdbcodata.security.rate-limit-per-minute`),
  danach antwortet der Service mit 429 für eine Minute.
- Hinterlegte DB-Passwörter sind AES-256-GCM-verschlüsselt (`JDBCODATA_MASTER_KEY`), Admin- und
  Service-User-Passwörter bcrypt-gehasht. Pass-Through-Passwörter werden nicht gespeichert.
- Alle DB-Verbindungen sind read-only, SQL ist auf `SELECT` beschränkt, Parameter werden gebunden.
- Pro Datenquelle gelten Timeout, Zeilenlimit und maximale parallele Ausführungen; darüber 503 mit `Retry-After`.
- Konfigurationsänderungen stehen im Audit-Log (Admin-UI → Audit, `GET /api/v1/audit`).

## Fehlersuche

| Symptom | Ursache / Abhilfe |
|---|---|
| Start bricht ab: „Refusing to start without TLS“ | `server.ssl.*` konfigurieren oder `jdbcodata.security.allow-plain-http=true` hinter einem Proxy |
| Excel fragt immer wieder nach Zugangsdaten | DB lehnt Login ab (401). In Admin-UI → Statistik den Status `AUTH_FAILED` prüfen; bei Service-Usern Passwort/hinterlegte Credentials prüfen |
| Query fehlt im Excel-Navigator | Query inaktiv, ohne Schema-Snapshot, oder FIXED ohne Freigabe für den Aufrufer |
| 503 „busy“ | `maxConcurrent` der Datenquelle erhöhen oder Cache aktivieren |
| 413 | Ergebnis über Zeilenlimit; Parameter oder `$top` nutzen, Limit erhöhen |
| 429 | Zu viele Fehlversuche; eine Minute warten, Passwort prüfen |
| „encrypted with key … but the configured master key is …“ | Master-Key gewechselt; alten Key wieder einsetzen oder Credentials neu hinterlegen |
