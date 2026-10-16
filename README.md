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

Entwurfsphase. Die Umsetzung beginnt mit Meilenstein M1 (siehe PRD §11) nach Freigabe des PRD.

## Geplanter Projektaufbau

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
│   └── config/           # Konfiguration, Master-Key
├── src/main/resources/
│   ├── db/migration/     # Flyway
│   └── templates/        # Thymeleaf
├── contrib/              # systemd-Unit, WinSW, Beispiel-Konfiguration
├── docker/               # Dockerfile, docker-compose.yml, Caddyfile
└── pom.xml
```
