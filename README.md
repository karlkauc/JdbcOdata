# JdbcOdata

Stellt SQL-Abfragen gegen firmeninterne Datenbanken (Oracle, PostgreSQL, MySQL/MariaDB) als OData-V4-Feeds für Excel und PowerBI bereit.

- Abfragen werden zur Laufzeit über Web-UI und REST-API angelegt, bearbeitet und gelöscht.
- Jede Abfrage läuft unter den DB-Berechtigungen des anfragenden Users (Basic Auth, kein SSO), optional unter hinterlegten Credentials oder einem technischen Account.
- Ergebnisse werden pro User gecacht, um Produktions-DBs zu entlasten.
- Jede Ausführung wird protokolliert (wer, wann, welche Query, Dauer, Zeilen, Fehler).
- Läuft nativ auf Linux und Windows (Java 21, ein JAR) und als Docker-Compose-Umgebung.

## Dokumente

| Dokument | Inhalt |
|---|---|
| [anforderung.txt](anforderung.txt) | Ursprüngliche Anforderung |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Architektur: Module, Request-Fluss, OData-Modell, Identität, Cache, Statistik, Deployment, Entscheidungen |
| [docs/PRD.md](docs/PRD.md) | Project Requirement Document: Scope, Use Cases, funktionale und nicht-funktionale Anforderungen, Datenmodell, API, Sicherheit, Tests, Meilensteine |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Betrieb: native Installation, Docker Compose, TLS, Backup, Monitoring, Fehlersuche |
| [docs/CONFIG.md](docs/CONFIG.md) | Konfigurationsreferenz |
| [docs/ADMIN_GUIDE.md](docs/ADMIN_GUIDE.md) | Datenquellen, Queries, Identität, Cache, Statistik einrichten |
| [docs/EXCEL_POWERBI.md](docs/EXCEL_POWERBI.md) | Anleitung für Excel und PowerBI |
| [docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) | Abnahme-Checkliste |

## Status

Release 1.0: alle vier Meilensteine des PRD sind umgesetzt. OData-Feed mit Basic-Auth-Pass-Through, `$filter`/`$orderby`
in-memory, Datenquellen- und Query-Verwaltung über REST-API und Admin-UI, Service-User mit verschlüsselt hinterlegten
DB-Credentials, technische Accounts mit FIXED-Modus und Freigaben, Ergebnis-Cache pro User, Ausführungsstatistik,
Audit-Log, Admin-Konten mit Rollen, TLS-Guard, Rate-Limiting, Concurrency-Limits, Prometheus-Metriken,
Docker-Compose mit PostgreSQL und Caddy, Distributions-ZIP mit Start-Skripten.

## Bauen und starten

Voraussetzung: Java 21. Maven kommt über den Wrapper mit.

```bash
./mvnw verify                      # Build, Tests, Coverage-Report (target/site/jacoco/index.html)
JDBCODATA_ADMIN_PASSWORD=geheim java -jar target/jdbcodata.jar --spring.profiles.active=demo
```

`./mvnw package` erzeugt zusätzlich `target/jdbcodata-1.0.0-dist.zip` mit Start-Skripten (`bin/jdbcodata.sh`,
`bin\jdbcodata.cmd`), Beispielkonfiguration und Doku. Ohne das Demo-Profil verlangt der Service TLS
(`server.ssl.*`) oder die Flag `jdbcodata.security.allow-plain-http=true`, siehe docs/OPERATIONS.md.

Windows: `mvnw.cmd verify` und `set JDBCODATA_ADMIN_PASSWORD=geheim` vor dem `java -jar`.

Das Demo-Profil legt unter `./data/demo` eine H2-Datenbank mit der Tabelle `sales` an (DB-User `demo`,
Passwort `demo`) und registriert die Query `Sales` als OData-Feed.

| Was | URL | Login |
|---|---|---|
| OData-Service-Dokument | `http://localhost:8080/odata/` | DB-User, z.B. `demo` / `demo` |
| `$metadata` | `http://localhost:8080/odata/$metadata` | DB-User |
| Daten | `http://localhost:8080/odata/Sales?p_year=2025&$top=100` | DB-User |
| Admin-UI | `http://localhost:8080/admin` | `admin` / `JDBCODATA_ADMIN_PASSWORD` (beim ersten Start angelegt) |
| Admin-API | `http://localhost:8080/api/v1/datasources`, `/api/v1/queries` | `admin` / `JDBCODATA_ADMIN_PASSWORD` |
| Health | `http://localhost:8080/actuator/health` | offen |

In Excel: *Daten → Daten abrufen → Aus anderen Quellen → Aus OData-Feed*, URL `http://localhost:8080/odata/`,
Anmeldung *Standard* mit DB-User und Passwort. Ohne TLS nur lokal verwenden; TLS kommt mit M4.

Eigene Datenquellen und Queries werden in der Admin-UI angelegt: Datenquelle mit JDBC-URL, dann Query mit SQL
(nur SELECT, Parameter als `:name`), Testlauf mit eigenen DB-Zugangsdaten, Schema-Snapshot erstellen. Danach
erscheint die Query als EntitySet im Feed. Alternativ: YAML-Datei nach dem Muster von
`src/main/resources/seed-demo.yml` mit `--jdbcodata.seed.file=config/seed.yml` laden oder die REST-API nutzen.

### Identität in Kürze

- **Pass-Through:** Excel sendet DB-User und DB-Passwort, der Service verbindet sich damit. Nichts wird gespeichert.
- **Beziehungen:** Queries können aufeinander verweisen (`Umsatz.Kunde → Kunden`); das `$metadata` enthält
  dann NavigationProperties, `$expand` lädt die verknüpften Zeilen mit (`/odata/Umsatz?$expand=Kunde`), und
  `$filter`/`$orderby` können ihnen folgen (`Kunde/Region eq 'Nord'`, `Umsaetze/any(u:u/Betrag gt 1000)`);
  Navigationspfade wie `Umsatz(1)/Kunde` funktionieren ebenso.
- **Protokoll:** serverseitiges Paging (`page-size`, `Prefer: odata.maxpagesize`), ETags mit 304, Parameter-Aliase
  und `$batch` (nur Lesen).
- **Suche und Aggregation:** `$search`, `$apply` (`groupby` mit `rollup`, `aggregate` mit `from` und Inline-Aggregaten,
  `topcount`, …) und `$compute` werden in-memory ausgewertet; dazu `$levels`, `$ref`, `$all`, `$crossjoin`,
  `cast`/`isof`/`$it`/`$root`.
- **Push-Down (pro Query):** `$filter`, `$search` (exakt oder als Vorfilter), `$orderby`, `$top`, `$skip`, `$count` und
  `$apply`-Gruppierungen samt folgendem `filter`/`orderby`/`top` laufen auf Wunsch als SQL in der
  Datenbank (PostgreSQL, H2, Oracle, MySQL/MariaDB), Nicht-Übersetzbares weiter in-memory; Header `X-Push-Down`.
- **Delta-Links:** `Prefer: odata.track-changes` liefert einen `@odata.deltaLink`; dessen Abruf meldet neue, geänderte
  und gelöschte Entitäten (Vergleich mit einem Schnappschuss in der Metadaten-DB, übersteht Neustarts).
- **Asynchron:** `Prefer: respond-async` liefert 202 und einen Status-Monitor (`/odata/$async/<id>`).
- **Collections und ComplexTypes:** SQL-Arrays und JSON-Spalten auf Wunsch als `Collection(Edm.X)`, Collection eines
  ComplexTypes oder ComplexType (Struktur aus dem Snapshot, im Admin bearbeitbar, optional zusätzlich als Text), mit
  `any`/`all`, `/$count`, Pfaden wie `Kundendaten/Land`, `$search` und `$select` mit Optionen
  (`$select=Positionen($filter=Betrag gt 100;$orderby=Betrag desc;$top=3;$count=true)`).
- **Weitere `$apply`-Transformationen:** `join`/`outerjoin`, `nest`, Hierarchien (`ancestors`, `descendants`, `traverse`), die
  Transformation `expand` und eigene Aggregationsmethoden (`JdbcOdata.median`, `stddev`, `variance`).
- **4.01-Schreibweisen:** `case(…)`, `$filter` als Pfadsegment, `matchesPattern`, `Umsaetze/$count($filter=…)`, Schlüssel als Pfadsegment (`Kunden/1`) und
  Leerzeichen nach dem Lambda-Doppelpunkt, die Olingo 5 selbst nicht parst, werden vorher umgeschrieben.
- **Parameter als Funktion:** Queries mit Parametern sind auch als `GetUmsatz(p_jahr=2024)` aufrufbar, komponierbar
  mit Schlüssel, Navigation, `$count` und allen Optionen.
- **Anonym (optional):** Mit `jdbcodata.odata.anonymous-user` laufen Anfragen ohne Login als ein Service-User, z. B. für
  einen öffentlichen Demo-Feed, den Excel oder der [OData Online Viewer](https://www.odata-viewer.online) ohne
  Passwortabfrage öffnen.
- **Service-User:** Konto im Service (empfohlen mit Präfix `svc_`), pro Datenquelle hinterlegte DB-Zugangsdaten,
  verschlüsselt mit dem Master-Key (`JDBCODATA_MASTER_KEY`, sonst automatisch `data/master.key`).
- **FIXED-Modus:** Query läuft unter einem technischen Account; sichtbar nur für freigegebene Service-User,
  Gruppen oder alle authentifizierten Aufrufer.

### Docker

```bash
cd docker
cp .env.example .env            # Host, Admin-Passwort, Master-Key, PostgreSQL-Passwort setzen
docker compose up -d --build    # Caddy (TLS, Port 443) + JdbcOdata + PostgreSQL
docker compose -f docker-compose.demo.yml up -d   # nur Demo: ein Container, H2, Port 8080
```

Hinter einem vorhandenen Apache mit Let's Encrypt und einer Demo-PostgreSQL mit Beispieldaten: siehe
`deploy/odata.status20.net/README.md`.

### Tests

`./mvnw verify` führt Unit- und Integrationstests aus (H2 in-memory, kein Docker nötig) und bricht ab, wenn die
Zeilenabdeckung unter 85 % fällt (JaCoCo, Schwelle in `pom.xml`).

## Projektaufbau

```
JdbcOdata/
├── docs/                 # Architektur, PRD, Betriebs- und Admin-Doku
├── src/main/java/at/jdbcodata/
│   ├── odata/            # Olingo-Endpunkt, dynamisches EDM
│   ├── admin/api/        # REST-API
│   ├── admin/ui/         # Thymeleaf-Controller der Admin-UI
│   ├── query/            # Query-Definitionen, Validierung, Ausführung
│   ├── identity/         # Identitätsauflösung, Credentials
│   ├── cache/            # Ergebnis-Cache
│   ├── stats/            # Execution-Log, Statistik
│   ├── datasource/       # Datenquellen, Pools, Treiber
│   ├── seed/             # YAML-Seed beim Start
│   ├── demo/             # Demo-Datenbank (Profil demo)
│   └── config/           # Konfiguration, Security
├── src/main/resources/
│   ├── db/migration/     # Flyway
│   ├── templates/        # Thymeleaf-Seiten (layout, login, admin/…)
│   └── static/           # CSS und JS der Admin-UI (htmx und CodeMirror kommen als WebJars)
├── src/test/java/        # Unit- und Integrationstests (JUnit 5, Spring Boot Test, JaCoCo)
├── bin/                  # Start-Skripte für das Distributions-ZIP
├── contrib/              # systemd-Unit, WinSW, Beispiel-Konfiguration, Seed-Beispiel
├── docker/               # Dockerfile, docker-compose.yml (Caddy + PostgreSQL), docker-compose.demo.yml
├── deploy/               # Deployment-Beispiel: Host-Apache + Docker-Stack + Demo-PostgreSQL (odata.status20.net)
├── e2e/                  # Playwright-Browser-Check und Screenshots
└── pom.xml
```
