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

Meilenstein M1 ist umgesetzt (siehe PRD §11): Datenquellen-API, Queries aus YAML-Seed, OData-Feed mit
Basic-Auth-Pass-Through, Demo-Profil, Dockerfile. Als Nächstes folgt M2 (Query-Verwaltung mit Admin-UI).

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
| Admin-API | `http://localhost:8080/api/v1/datasources`, `/api/v1/queries` | `admin` / `JDBCODATA_ADMIN_PASSWORD` |
| Health | `http://localhost:8080/actuator/health` | offen |

In Excel: *Daten → Daten abrufen → Aus anderen Quellen → Aus OData-Feed*, URL `http://localhost:8080/odata/`,
Anmeldung *Standard* mit DB-User und Passwort. Ohne TLS nur lokal verwenden; TLS kommt mit M4.

Eigene Datenquellen und Queries: YAML-Datei nach dem Muster von `src/main/resources/seed-demo.yml` anlegen und
mit `--jdbcodata.seed.file=config/seed.yml` laden, oder Datenquellen über die REST-API pflegen.

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
│   ├── admin/            # Web-UI und REST-API
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
│   └── templates/        # Thymeleaf
├── src/test/java/        # Unit- und Integrationstests (JUnit 5, Spring Boot Test, JaCoCo)
├── contrib/              # systemd-Unit, WinSW, Beispiel-Konfiguration (M4)
├── docker/               # Dockerfile, docker-compose.yml, .env.example
└── pom.xml
```
