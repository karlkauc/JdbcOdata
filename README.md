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

## Status

Meilensteine M1 bis M3 sind umgesetzt (siehe PRD §11): OData-Feed mit Basic-Auth-Pass-Through, `$filter`/`$orderby`
in-memory, Datenquellen- und Query-Verwaltung über REST-API und Admin-UI, Service-User mit verschlüsselt hinterlegten
DB-Credentials, technische Accounts mit FIXED-Modus und Freigaben, Ergebnis-Cache pro User, Ausführungsstatistik,
Audit-Log und Admin-Konten mit Rollen. Als Nächstes folgt M4 (Docker-Compose mit PostgreSQL und TLS, Rate-Limiting,
Start-Skripte, Betriebsdokumentation).

## Bauen und starten

Voraussetzung: Java 21. Maven kommt über den Wrapper mit.

```bash
./mvnw verify                      # Build, Tests, Coverage-Report (target/site/jacoco/index.html)
JDBCODATA_ADMIN_PASSWORD=geheim java -jar target/jdbcodata.jar --spring.profiles.active=demo
```

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
- **Service-User:** Konto im Service (empfohlen mit Präfix `svc_`), pro Datenquelle hinterlegte DB-Zugangsdaten,
  verschlüsselt mit dem Master-Key (`JDBCODATA_MASTER_KEY`, sonst automatisch `data/master.key`).
- **FIXED-Modus:** Query läuft unter einem technischen Account; sichtbar nur für freigegebene Service-User,
  Gruppen oder alle authentifizierten Aufrufer.

### Docker

```bash
cd docker
cp .env.example .env            # Admin-Passwort setzen
docker compose up -d --build
```

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
├── contrib/              # systemd-Unit, WinSW, Beispiel-Konfiguration (M4)
├── docker/               # Dockerfile, docker-compose.yml, .env.example
└── pom.xml
```
