# JdbcOdata – Architektur

Stand: 2026-10-16 · Status: Release 1.0 (M1 bis M4 umgesetzt)

JdbcOdata stellt SQL-Abfragen gegen firmeninterne Datenbanken als OData-V4-Feeds für Excel und PowerBI bereit. Abfragen werden zur Laufzeit von Admins verwaltet, laufen unter der DB-Identität des anfragenden Users, werden pro User gecacht und vollständig protokolliert.

## 1. Überblick

```mermaid
flowchart LR
    subgraph Clients
        XL[Excel<br/>Power Query]
        BI[PowerBI<br/>Desktop / Gateway]
        AD[Admin-Browser]
    end

    subgraph Service["JdbcOdata-Service (Spring Boot, 1 Prozess)"]
        OD["/odata<br/>Olingo OData V4"]
        UI["/admin<br/>Web-UI"]
        API["/api/v1<br/>REST-API"]
        ID[Identity<br/>Resolver]
        QE[Query<br/>Engine]
        CA[Result<br/>Cache]
        ST[Statistik /<br/>Execution Log]
        DS[Datasource<br/>Registry + Pools]
    end

    subgraph Meta["Metadaten-DB"]
        H2[(H2 embedded<br/>oder PostgreSQL)]
    end

    subgraph Target["Ziel-Datenbanken"]
        ORA[(Oracle)]
        PG[(PostgreSQL)]
        MY[(MySQL /<br/>MariaDB)]
    end

    XL -- HTTPS + Basic Auth --> OD
    BI -- HTTPS + Basic Auth --> OD
    AD -- HTTPS + Admin-Login --> UI
    UI --> API
    OD --> ID --> QE
    QE --> CA
    QE --> DS
    QE --> ST
    API --> QE
    CA --> H2
    ST --> H2
    API --> H2
    DS -- JDBC --> ORA
    DS -- JDBC --> PG
    DS -- JDBC --> MY
```

**Technologie-Stack**

| Baustein | Wahl | Begründung |
|---|---|---|
| Sprache / Runtime | Java 21 (LTS) | Läuft nativ auf Linux und Windows, ein Artefakt für alle Plattformen |
| Framework | Spring Boot 4.x (Spring Framework 7, Spring Security 7, Hibernate 7) | Spring Boot 3.5 ist seit Mitte 2026 aus dem OSS-Support; 4.x ist die gepflegte Linie |
| OData | Apache Olingo 5.0 (Server-Core + JSON, Jakarta Servlet) | Reifste OData-V4-Server-Implementierung in Java; parst URL und Query-Optionen, serialisiert JSON und `$metadata` |
| DB-Zugriff | JDBC + HikariCP | Oracle, PostgreSQL, MySQL/MariaDB über offizielle Treiber; weitere DBs ohne Codeänderung |
| SQL-Validierung | JSqlParser | Nur `SELECT`/`WITH` zulassen, Parameter erkennen |
| Metadaten-DB | H2 (Datei) standardmäßig, PostgreSQL optional | Keine Zusatzinstallation lokal, Mehrinstanz-Betrieb in Docker |
| Persistenz | Spring Data JPA + Flyway | Schema-Migrationen versioniert, Upgrade-Pfad |
| Admin-UI | Thymeleaf + htmx + CodeMirror | Kein Node-Build, im JAR gebündelt |
| Cache (hot) | Caffeine | In-Memory-Layer vor der persistenten Cache-Tabelle |
| Build | Maven, Fat-JAR | `java -jar`, Multi-Stage-Dockerfile |

## 2. Module

Ein einzelner Spring-Boot-Prozess, intern in Packages unter `at.jdbcodata.*` geschnitten. Abhängigkeiten laufen nur von oben nach unten.

| Package | Verantwortung | Wichtige Klassen (geplant) |
|---|---|---|
| `odata` | Olingo-Servlet unter `/odata`, dynamischer `CsdlEdmProvider` aus Query-Definitionen, Service-Dokument, `$metadata`, `EntityCollectionProcessor` | `DynamicEdmProvider`, `QueryEntityCollectionProcessor`, `ODataQueryOptionEvaluator` |
| `admin` | Web-UI (`/admin`) und REST-API (`/api/v1`), Admin-Authentifizierung, Rollen | `QueryController`, `DatasourceController`, `StatsController` |
| `query` | Query-Definitionen (CRUD), SQL-Validierung, Parameter-Deklaration, Schema-Snapshot, Ausführung | `QueryDefinition`, `QueryValidator`, `SchemaSnapshotService`, `QueryExecutor` |
| `identity` | Auflösung der Ausführungsidentität, Service-User, hinterlegte Credentials, Verschlüsselung | `ExecutionIdentityResolver`, `CredentialVault`, `ServiceUser` |
| `cache` | Ergebnis-Cache pro (Query, DB-User, Parameter), TTL, Invalidierung | `ResultCache`, `CacheKey`, `CacheEntry` |
| `stats` | Ausführungsprotokoll, Aggregationen, Retention, Micrometer-Metriken | `ExecutionLog`, `StatsService`, `RetentionJob` |
| `datasource` | Datenquellen-Registry, Connection-Pools pro Identität, Treiber-Laden | `DatasourceRegistry`, `IdentityPoolManager`, `DriverLoader` |
| `config` | `application.yml`, Env-Vars, Master-Key, TLS, Initial-Admin | `JdbcOdataProperties`, `MasterKeyProvider` |

## 3. Request-Fluss (OData-Abruf aus Excel)

```mermaid
sequenceDiagram
    autonumber
    participant XL as Excel / PowerBI
    participant OD as OData-Endpunkt
    participant ID as IdentityResolver
    participant CA as ResultCache
    participant QE as QueryExecutor
    participant DS as Ziel-DB
    participant ST as ExecutionLog

    XL->>OD: GET /odata/Umsatz?p_jahr=2025&$top=1000<br/>Authorization: Basic …
    OD->>OD: Query "Umsatz" laden, Parameter validieren
    OD->>ID: resolve(query, basicAuth)
    alt Modus FIXED
        ID-->>OD: technischer DB-User der Query
    else Service-User bekannt
        ID->>ID: Service-Passwort prüfen
        ID-->>OD: hinterlegte DB-Credentials (oder Pass-Through)
    else kein Service-User
        ID-->>OD: Pass-Through (Basic-Auth = DB-Login)
    end
    OD->>CA: get(queryId, dbUser, params)
    alt Cache-Hit und nicht abgelaufen
        CA-->>OD: Zeilen
    else Cache-Miss oder ?refresh=true
        OD->>QE: execute(query, identity, params)
        QE->>DS: PreparedStatement (readOnly, timeout, maxRows)
        DS-->>QE: ResultSet
        QE->>CA: put(key, Zeilen, ttl)
        QE-->>OD: Zeilen
    end
    OD->>OD: $filter/$orderby/$select/$top/$skip in-memory anwenden
    OD->>ST: log(aufrufer, dbUser, dauer, zeilen, cacheStatus, status)
    OD-->>XL: 200 OK, application/json, X-Cache: HIT|MISS
```

Fehlerfälle: DB-Login schlägt fehl → `401` mit `WWW-Authenticate: Basic`, damit Excel erneut nach Credentials fragt. Query-Fehler → `500` mit OData-Fehlerobjekt, Fehlermeldung landet im Log, aber ohne SQL-Text im Response.

## 4. OData-Modell

**Eine Query ist ein EntitySet.** Der Admin vergibt einen Identifier (`[A-Za-z][A-Za-z0-9_]*`), der als EntitySet-Name im Service-Dokument erscheint. Der EntityType heißt gleich, Namespace `JdbcOdata`.

**Schema-Snapshot.** Beim Speichern oder Testen einer Query wird sie einmal mit `setMaxRows(1)` unter den Test-Credentials des Admins ausgeführt. Die `ResultSetMetaData` (Spaltenname, JDBC-Typ, Precision, Scale, Nullable) wird in `query_column` persistiert. `$metadata` wird ausschließlich aus dem Snapshot gebaut und braucht keinen DB-Zugriff. Ändert sich das DB-Schema, aktualisiert der Admin den Snapshot per Knopfdruck.

**Schlüssel.** OData verlangt eine Key-Property. Standard ist ein synthetisches `RowId` (`Edm.Int64`, Zeilenindex des Ergebnisses). Alternativ markiert der Admin eine oder mehrere Spalten als Key.

**Typmapping JDBC → EDM**

| JDBC-Typ | EDM-Typ | Hinweis |
|---|---|---|
| CHAR, VARCHAR, NVARCHAR, CLOB, NCLOB | `Edm.String` | CLOB wird vollständig gelesen |
| TINYINT, SMALLINT, INTEGER | `Edm.Int32` | |
| BIGINT | `Edm.Int64` | |
| NUMERIC, DECIMAL | `Edm.Decimal` | Precision/Scale aus Metadaten; Oracle `NUMBER` ohne Scale → `Edm.Decimal` |
| REAL, FLOAT, DOUBLE | `Edm.Double` | |
| DATE | `Edm.Date` | |
| TIME | `Edm.TimeOfDay` | |
| TIMESTAMP, TIMESTAMP WITH TIME ZONE | `Edm.DateTimeOffset` | Ohne Zone: Server-Zeitzone, konfigurierbar |
| BOOLEAN, BIT | `Edm.Boolean` | |
| BINARY, VARBINARY, BLOB | `Edm.Binary` | Standardmäßig aus dem EntityType ausgeblendet |
| Sonstige | `Edm.String` | `toString()`, mit Warnung im Snapshot |

**Query-Optionen.** `$top`, `$skip`, `$filter`, `$select`, `$orderby`, `$count` werden von Olingo geparst und **in-memory** auf der vollständigen Ergebnismenge (aus Cache oder DB) ausgewertet (`QueryOptionsEvaluator`, `EntityExpressionVisitor`). Reihenfolge: `$filter` → `$orderby` → `$count` → `$skip` → `$top`. Unterstützt in `$filter`: Vergleiche, `and`/`or`/`not`, `in`, Arithmetik, String-Funktionen (`contains`, `startswith`, `endswith`, `length`, `indexof`, `substring`, `tolower`, `toupper`, `trim`, `concat`), Datumsfunktionen (`year` … `second`, `date`, `time`, `now`), `round`/`floor`/`ceiling`. Das ist bewusst, weil die Ergebnismenge ohnehin pro User gecacht wird und Excel beim Laden meist den gesamten Feed holt. Push-Down von `$filter`/`$top` in SQL (Subquery-Wrapping) ist Phase 2. Nicht unterstützt: `$expand`, `$search`, `$apply`, Lambda-Ausdrücke.

**Query-Parameter.** SQL darf benannte Parameter `:name` enthalten. Jeder Parameter wird mit Typ (`STRING`, `INT`, `DECIMAL`, `DATE`, `TIMESTAMP`, `BOOLEAN`), Pflicht-Flag und optionalem Default deklariert. Clients übergeben Werte als **Custom Query Options** (`?p_jahr=2025`; OData erlaubt Optionen ohne `$`-Präfix, Excel reicht sie unverändert durch). Werte werden ausschließlich per `PreparedStatement` gebunden und sind Teil des Cache-Keys.

**Format.** JSON nach OData V4 (`application/json;odata.metadata=minimal`). Excel „Daten abrufen → Aus OData-Feed“ und der PowerBI-OData-Connector verwenden V4/JSON und unterstützen Basic Auth nativ.

## 5. Identität und Berechtigungen

Jeder OData-Aufruf trägt Basic-Auth-Credentials (daher HTTPS-Pflicht). Einzige Ausnahme: ist `jdbcodata.odata.anonymous-user` gesetzt, laufen Anfragen **ohne** `Authorization`-Header als dieser Service-User (ohne Passwortprüfung, nur mit hinterlegten Credentials bzw. FIXED-Queries; fehlen beide, antwortet der Service mit 401). Die **Ausführungsidentität** ist der DB-User, unter dem die Abfrage tatsächlich läuft. Sie wird in dieser Reihenfolge aufgelöst:

```mermaid
flowchart TD
    A[Basic-Auth: user / pass] --> B{Query-Modus?}
    B -- FIXED --> C{Service-User<br/>bekannt?}
    C -- ja, Passwort ok --> D[DB-Identität = technischer<br/>Account der Query]
    C -- ja, Passwort falsch --> X[401]
    C -- nein --> E{Pass-Through-Login<br/>gegen Datenquelle ok?}
    E -- ja --> D
    E -- nein --> X
    B -- CALLER --> F{Service-User<br/>bekannt?}
    F -- ja, Passwort ok --> G{Credentials für<br/>Datenquelle hinterlegt?}
    G -- ja --> H[DB-Identität = hinterlegte<br/>DB-Credentials]
    G -- nein --> I[DB-Identität = user / pass<br/>Pass-Through]
    F -- ja, Passwort falsch --> X
    F -- nein --> I
    D --> Z[Freigabe-Prüfung: darf<br/>Aufrufer diese Query sehen?]
    H --> Z
    I --> Z
    Z -- ja --> OK[Ausführen]
    Z -- nein --> N[404 / nicht im<br/>Service-Dokument]
```

**Modi einer Query**

| Modus | Bedeutung | Typischer Einsatz |
|---|---|---|
| `CALLER` (Standard) | Läuft unter der Identität des Aufrufers (Pass-Through oder hinterlegte Credentials) | Daten, bei denen die DB selbst die Berechtigungen regelt (Row-Level-Security, Views, Grants) |
| `FIXED` | Läuft unter einem auf der Query hinterlegten technischen DB-Account; der Aufrufer wird nur gegen den Service bzw. die Datenquelle authentifiziert | Aggregierte Reports für User ohne eigenes DB-Konto; „Abfrage läuft unter den Berechtigungen eines anderen Users“ |

**Service-User.** Optionales Konto im Service mit eigenem Passwort (bcrypt), Namenskonvention Präfix `svc_`, damit es keine Kollision mit DB-Usernamen gibt. Pro Datenquelle können DB-Credentials hinterlegt werden. Vorteile: ein Login in Excel für mehrere Datenquellen, DB-Passwort-Rotation ohne Excel-Änderung, Zugriff auf `FIXED`-Queries ohne DB-Konto. Ein Flag „Pass-Through erzwingen“ schaltet hinterlegte Credentials für einen Service-User ab. Ohne Service-User gilt reines Pass-Through, der Service speichert nichts.

**PowerBI Service über Gateway.** Das On-Premises Data Gateway speichert die Basic-Auth-Credentials selbst. Deshalb werden dort ausschließlich Service-User mit hinterlegten Credentials eingetragen, nie persönliche DB-Konten.

**Sichtbarkeit.** Das Service-Dokument und `$metadata` zeigen nur Queries, die der Aufrufer sehen darf. Bei `CALLER`-Queries ist das jede Query der Datenquelle, gegen die sich der Aufrufer authentifizieren kann (die DB entscheidet beim Ausführen). Bei `FIXED`-Queries pflegt der Admin eine Freigabeliste (Service-User oder Gruppen). Nicht freigegebene Queries antworten mit `404`, nicht `403`, um keine Existenz zu verraten.

**Credentials at rest.** Hinterlegte DB-Passwörter und `FIXED`-Accounts werden mit AES-256-GCM verschlüsselt (`CredentialVault`: Zufalls-IV pro Eintrag, Key-ID im Datensatz für Rotation). Der Master-Key kommt aus `JDBCODATA_MASTER_KEY` (Base64, 32 Byte); ist nichts konfiguriert, erzeugt `MasterKeyProvider` beim ersten Start eine Key-Datei `data/master.key` (Rechte 600) und warnt im Log. Der Key liegt nie in der Metadaten-DB. Pass-Through-Passwörter werden nie persistiert und nie geloggt; im Prozess leben sie nur im Request-Scope und im Pool-Schlüssel als Hash.

**Connections.**

- Pass-Through und hinterlegte Identitäten: HikariCP-Pool pro `(Datenquelle, DB-User, SHA-256(Passwort))`, `maximumPoolSize=2`, `idleTimeout=5 min`, Pool wird nach Inaktivität komplett geschlossen. Excel-Refreshes treffen damit einen warmen Pool, Credentials bleiben aber nicht dauerhaft offen.
- `FIXED`-Accounts: regulärer Pool pro Query-Account, Größe konfigurierbar.
- Jede Connection: `setReadOnly(true)`, `setAutoCommit(false)` + Rollback am Ende, Statement-Timeout und `maxRows` aus Datenquelle bzw. Query (Query darf Datenquellen-Limit nur unterschreiten).

**SQL-Sicherheit.** SQL wird nur von Admins geschrieben, ist also vertrauenswürdig, aber: JSqlParser erzwingt `SELECT`/`WITH` (keine DML/DDL, kein `INTO`), Parameter werden nie konkateniert, die DB-Connection ist read-only, und pro Datenquelle gelten Timeout und Zeilenlimit. Fehlermeldungen an Clients enthalten nie SQL-Text. Jede Änderung an SQL, Parametern oder Datenquelle erzeugt einen Eintrag in `query_version` (Autor, Zeit, Kommentar); ältere Versionen können wiederhergestellt werden.

## 6. Caching

| Aspekt | Design |
|---|---|
| Key | `(queryId, queryVersion, effektiver DB-User, normalisierte Parameterwerte)` – User sehen nur Ergebnisse, die unter ihren eigenen DB-Rechten entstanden sind |
| Konfiguration pro Query | `cacheEnabled`, `ttlSeconds`, `maxCachedRows`; Datenquelle setzt Obergrenzen |
| Storage | Tabelle `cache_entry` in der Metadaten-DB: Rows als GZIP-komprimierte Spalten-Blobs (Columnar, damit `$select` billig ist). Caffeine davor für heiße Einträge (Größe in MB begrenzt) |
| Mehrinstanz | Mit PostgreSQL als Metadaten-DB teilen sich alle Instanzen den persistenten Cache; Caffeine ist lokal und nur Beschleunigung |
| Invalidierung | TTL-Ablauf; manuell per Admin-UI/API pro Query oder Query+User; per Request `?refresh=true` (nur für authentifizierte Aufrufer, zählt als Miss); automatisch bei Änderung der Query (Versionsnummer im Key) |
| Stampede-Schutz | Pro Key ein Lock: parallele Excel-Refreshes desselben Users lösen nur eine DB-Ausführung aus |
| Transparenz | Response-Header `X-Cache: HIT|MISS|BYPASS` und `X-Cache-Age: <Sekunden>` |
| Aufräumen | Hintergrundjob löscht abgelaufene Einträge; Gesamtgröße des Caches ist konfigurierbar (LRU) |

Cache-Hit bedeutet keine Query-Ausführung: OData-Optionen werden vollständig auf dem gecachten Ergebnis ausgewertet. Einzige Ausnahme ist die Login-Prüfung bei reinem Pass-Through (Aufrufer ohne Service-User-Konto): Der Cache-Schlüssel enthält nur den DB-Usernamen, deshalb holt der Service vor der Auslieferung eines Treffers eine Verbindung aus dem Identitäts-Pool des Aufrufers; ein falsches Passwort führt zu 401 statt zu gecachten Daten. Bei bestehendem Pool kostet das keinen DB-Roundtrip. Umsetzung: `ResultCache` (Caffeine mit Gewicht nach Zeilen × Spalten und Ablauf je Eintrag, darunter Tabelle `cache_entry` mit GZIP-komprimiertem Binärformat aus `ResultTableCodec`), Per-Key-Lock gegen parallele Ausführungen, Aufräumjob alle 5 Minuten (abgelaufene Einträge, LRU bei Überschreitung von `jdbcodata.cache.max-size-mb`).

## 7. Statistiken und Protokollierung

Jede Ausführung schreibt einen Datensatz nach `execution_log` (asynchron, damit die Antwort nicht wartet):

| Feld | Inhalt |
|---|---|
| `started_at`, `finished_at` | Zeitstempel |
| `query_id`, `query_name`, `query_version` | Welche Query |
| `datasource_id` | Welche DB |
| `caller` | Basic-Auth-Username bzw. Service-User |
| `effective_db_user` | DB-Identität |
| `execution_mode` | `CALLER` / `FIXED` |
| `client` | Aus `User-Agent` abgeleitet: `EXCEL`, `POWERBI`, `OTHER` + Rohwert |
| `client_ip` | Quelle (mit `X-Forwarded-For` hinter Proxy) |
| `parameters` | JSON, Werte sensitiv markierbarer Parameter maskiert |
| `odata_options` | `$filter`, `$top` usw. als Text |
| `cache_status` | `HIT` / `MISS` / `BYPASS` / `DISABLED` |
| `db_duration_ms`, `total_duration_ms` | DB-Zeit und Gesamtzeit |
| `row_count`, `bytes_sent` | Ergebnisgröße |
| `status` | `OK` / `AUTH_FAILED` / `DB_ERROR` / `TIMEOUT` / `VALIDATION_ERROR` |
| `error_message` | Gekürzte Fehlermeldung ohne SQL |

Admin-UI „Statistiken“: Top-Queries nach Aufrufen, langsamste Queries (p50/p95), Fehlerquote pro Query und Datenquelle, Nutzung pro User, Cache-Trefferquote, Zeitverlauf (Tag/Woche), Rohdaten-Tabelle mit Filter und CSV-Export. Retention konfigurierbar (Default 90 Tage), Aggregat-Tabelle pro Tag für Langzeitauswertung. Zusätzlich Micrometer-Metriken unter `/actuator/prometheus` (optional aktivierbar).

Audit: Änderungen an Queries, Datenquellen, Service-Usern, Gruppen, technischen Accounts, Admin-Konten, Freigaben und Cache-Invalidierungen landen in `audit_log` (wer, wann, Aktion, Objekt, Details als JSON ohne Secrets). Das Execution-Log wird von einem eigenen Writer-Thread geschrieben (`jdbcodata.stats.async`), die Retention läuft täglich.

## 8. Admin-UI und REST-API

**UI** unter `/admin`, server-gerendert (Thymeleaf), Interaktivität über htmx, SQL-Editor mit CodeMirror (gebündelt). Seiten:

| Seite | Funktionen |
|---|---|
| Datenquellen | Anlegen/Bearbeiten/Löschen, Verbindungstest, Limits (Timeout, Max-Zeilen, Pool-Größe) |
| Queries | Liste, Editor (Name, Datenquelle, SQL, Parameter, Modus, Cache-Einstellungen, Key-Spalten), Testlauf mit Vorschau, Schema-Snapshot aktualisieren, Freigaben, Versionshistorie |
| Service-User | Anlegen, Passwort setzen, DB-Credentials pro Datenquelle hinterlegen, Gruppen |
| Cache | Einträge pro Query/User, Größe, Alter, Invalidieren |
| Statistiken | siehe Abschnitt 7 |
| Einstellungen | Retention, Cache-Obergrenzen, Master-Key-Status, Lizenz-/Versionsinfo |

**REST-API** unter `/api/v1` (JSON, OpenAPI-Spezifikation unter `/api/docs`), genutzt von der UI und für Automatisierung:

| Ressource | Endpunkte |
|---|---|
| Datenquellen | `GET/POST /datasources`, `GET/PUT/DELETE /datasources/{id}`, `POST /datasources/{id}/test` |
| Queries | `GET/POST /queries`, `GET/PUT/DELETE /queries/{id}`, `POST /queries/{id}/test`, `POST /queries/{id}/snapshot`, `GET /queries/{id}/versions` |
| Service-User | `GET/POST /users`, `GET/PUT/DELETE /users/{id}`, `PUT/DELETE /users/{id}/credentials/{datasourceId}` |
| Cache | `GET /cache`, `DELETE /cache?queryId=&dbUser=` |
| Statistiken | `GET /stats/summary`, `GET /stats/executions?from=&to=&queryId=&caller=`, `GET /stats/export.csv` |

**Admin-Authentifizierung.** Drei Security-Chains: `/odata/**` ohne Spring-Security-Prüfung (Basic-Credentials gehen an die DB), `/api/**` mit zustandslosem Basic Auth, alles andere mit Session-Login-Formular (`/login`, CSRF aktiv, htmx sendet den Token im Header). Bis M3 gibt es ein Admin-Konto aus `JDBCODATA_ADMIN_USER` / `JDBCODATA_ADMIN_PASSWORD`; danach Admin-Accounts in `admin_user` (bcrypt), API-Token (Bearer) und die Rollen `ADMIN` und `VIEWER`.

**Endpunkt-Übersicht**

| Pfad | Zweck | Auth |
|---|---|---|
| `/odata/` | Service-Dokument | Basic |
| `/odata/$metadata` | EDM | Basic |
| `/odata/{EntitySet}` | Daten | Basic |
| `/admin/**` | Web-UI | Admin-Session |
| `/api/v1/**` | REST-API | Admin-Session oder Bearer |
| `/actuator/health` | Health | offen (nur Status) |
| `/actuator/prometheus` | Metriken | optional, IP-/Token-geschützt |

## 9. Persistenz der Metadaten

Spring Data JPA, Flyway-Migrationen unter `db/migration`. Standard H2 im Dateimodus (`./data/jdbcodata.mv.db`), optional PostgreSQL über `JDBCODATA_META_URL`. Tabellen:

| Tabelle | Inhalt |
|---|---|
| `datasource` | Name, JDBC-URL, Treiberklasse, Limits, Pool-Einstellungen, Zeitzone |
| `query_definition` | Name, Datenquelle, SQL, Modus, `FIXED`-Credential-Referenz, Cache-Einstellungen, Version, Status (aktiv/inaktiv) |
| `query_version` | Historie der Definition (SQL, Parameter als JSON, Datenquelle) mit Autor, Zeitstempel und Kommentar |
| `query_parameter` | Name, Typ, Pflicht, Default, sensitiv |
| `query_column` | Schema-Snapshot: Spalte, JDBC-Typ, EDM-Typ, Precision, Scale, Nullable, Key-Flag, sichtbar |
| `query_grant` | Freigaben für `FIXED`-Queries (Service-User oder Gruppe) |
| `service_user` | Login, bcrypt-Hash, aktiv, Gruppen |
| `stored_credential` | Service-User × Datenquelle → verschlüsselter DB-User/Passwort, Key-ID |
| `fixed_credential` | Technische Accounts für `FIXED`-Queries, verschlüsselt |
| `admin_user` | Login, bcrypt-Hash, Rolle, API-Token-Hash |
| `cache_entry` | Key, Query-Version, DB-User, Parameter-Hash, Blob, Zeilen, Bytes, erzeugt, läuft ab |
| `execution_log` | siehe Abschnitt 7 |
| `execution_daily` | Tagesaggregate pro Query/User |
| `audit_log` | Änderungen an Konfiguration |

## 10. Deployment

**Nativ (Linux, Windows).** Ein Fat-JAR `jdbcodata.jar`, Voraussetzung Java 21 (oder gebündelte JRE via `jlink`). Verzeichnislayout:

```
jdbcodata/
├── jdbcodata.jar
├── bin/jdbcodata.sh        # Linux-Start, setzt JAVA_OPTS, legt data/ an
├── bin/jdbcodata.cmd       # Windows-Start
├── config/application.yml  # Konfiguration (wird vor den Defaults im JAR geladen)
├── lib/                    # zusätzliche JDBC-Treiber (werden beim Start geladen)
├── data/                   # H2-Datei, Cache, Logs
└── certs/                  # Keystore für eingebautes TLS
```

`./mvnw package` erzeugt `target/jdbcodata-<version>-dist.zip` mit diesem Layout (maven-assembly-plugin). Das JAR nutzt das ZIP-Layout von Spring Boot, damit `-Dloader.path=lib` zusätzliche Treiber lädt. Vorlagen für systemd und WinSW liegen in `contrib/`; `jpackage` für Installer ist möglich, aber nicht umgesetzt.

**Docker.** Multi-Stage-Dockerfile (Maven-Build → `eclipse-temurin:21-jre-alpine`, Non-Root-User). `docker-compose.yml` mit drei Services:

```mermaid
flowchart LR
    C[Clients] -- 443 --> P[Caddy<br/>TLS-Terminierung]
    P -- 8080 --> S[jdbcodata]
    S --> M[(postgres<br/>Metadaten-DB)]
    S -. JDBC .-> T[(Firmen-DBs)]
    V1[volume: meta-data] --- M
    V2[volume: config, lib, certs] --- S
```

Alle Secrets (Master-Key, Admin-Passwort, Metadaten-DB-Passwort) über Env-Vars oder Docker-Secrets. Health-Check auf `/actuator/health`. Image-Tag = Release-Version.

**Konfiguration** (Auszug, alle Werte auch als Env-Var mit Präfix `JDBCODATA_`):

| Schlüssel | Default | Bedeutung |
|---|---|---|
| `server.port` | 8080 | HTTP-Port |
| `server.ssl.*` | aus | Eingebautes TLS |
| `jdbcodata.meta.url` | H2-Datei | Metadaten-DB |
| `jdbcodata.master-key` | – | Pflicht, Base64, 32 Byte |
| `jdbcodata.admin.user/password` | – | Initial-Admin |
| `jdbcodata.cache.max-size-mb` | 1024 | Persistenter Cache gesamt |
| `jdbcodata.cache.memory-mb` | 256 | Caffeine-Layer |
| `jdbcodata.stats.retention-days` | 90 | Protokoll-Aufbewahrung |
| `jdbcodata.odata.max-rows` | 500000 | Harte Obergrenze pro Ergebnis |
| `jdbcodata.security.rate-limit` | 20/min | Fehlversuche pro IP bei Basic Auth |
| `jdbcodata.timezone` | System | Für TIMESTAMP ohne Zone |

**TLS** ist Pflicht, weil Basic Auth Klartext-Credentials trägt. Entweder eingebaut (Keystore in `certs/`, `server.ssl.*`) oder Reverse-Proxy (Caddy im Compose-Stack, alternativ Traefik/IIS/nginx mit `server.forward-headers-strategy=framework`). Ohne TLS startet der Service nur mit expliziter Flag `jdbcodata.security.allow-plain-http=true` und Warnung im Log (`PlainHttpGuard`). Fehlgeschlagene Logins werden pro IP und Benutzername gezählt (`LoginRateLimiter`, `RateLimitFilter`), parallele Ausführungen pro Datenquelle begrenzt (`ConcurrencyLimiter`).

**JDBC-Treiber.** `ojdbc11`, `postgresql`, `mariadb-java-client` werden gebündelt (alle über Maven Central verfügbar; Oracle-Treiber unter Oracle Free Use Terms). Weitere Treiber (DB2, SQL Server, …) legt der Betrieb in `lib/`, der `DriverLoader` registriert sie beim Start.

## 11. Nicht-funktionale Leitplanken

| Thema | Ziel |
|---|---|
| Latenz Cache-Hit | < 200 ms bis zum ersten Byte bei 100 000 Zeilen |
| Ergebnisgröße | bis 500 000 Zeilen bzw. 256 MB pro Ergebnis (konfigurierbar), darüber Fehler mit Hinweis |
| Streaming | Antworten werden gestreamt (Olingo-Serializer auf `OutputStream`), nicht vollständig im Speicher aufgebaut |
| Parallelität | Pro Datenquelle konfigurierbares Limit gleichzeitiger DB-Ausführungen; darüber `503 Retry-After` |
| Verfügbarkeit | Stateless bis auf Metadaten-DB; mehrere Instanzen hinter Proxy mit PostgreSQL |
| Logging | Strukturiert (JSON optional), nie Passwörter oder SQL mit Parameterwerten |
| Tests | Unit; Integration mit Testcontainers (PostgreSQL, MariaDB, `gvenzl/oracle-free`); OData-Konformität mit Olingo-Client; manuelle Abnahme in Excel 365 und PowerBI Desktop |

## 12. Bewusst nicht in Phase 1

- Push-Down von `$filter`, `$orderby`, `$top` in SQL
- Schreibende OData-Operationen (POST/PATCH/DELETE)
- SSO (Kerberos, OAuth2/OIDC, Entra ID)
- OData V2 / Atom-Format
- Mandantenfähigkeit
- Verteilter Cache (Redis); der persistente Cache in PostgreSQL deckt Mehrinstanz-Betrieb ab
- Zeitgesteuertes Vorwärmen des Caches (Scheduled Refresh) – Kandidat für Phase 2

## 13. Entscheidungsprotokoll (ADR-Kurzform)

| # | Entscheidung | Alternativen | Grund |
|---|---|---|---|
| 1 | Java 21 + Spring Boot 4 + Olingo 5 | .NET 8 + ASP.NET OData; Python/FastAPI | JDBC deckt alle Firmen-DBs ab, Olingo ist die reifste OData-V4-Server-Lib, ein Artefakt für Linux/Windows/Docker |
| 2 | Basic Auth Pass-Through als Standard, Service-User optional | Nur Service-User mit hinterlegten Credentials | Kein Passwort-Speicher nötig für den Standardfall; Excel/PowerBI unterstützen Basic Auth nativ |
| 3 | Query-Modus `CALLER`/`FIXED` | Nur Caller-Identität | Deckt „Abfrage unter anderem User“ explizit und auditierbar ab |
| 4 | OData-Optionen in-memory | SQL-Push-Down | Ergebnis ist ohnehin pro User gecacht; Push-Down erst, wenn Messungen es verlangen |
| 5 | Schema-Snapshot statt Live-Metadaten | `$metadata` per DB-Abfrage | `$metadata` muss schnell und ohne User-Credentials verfügbar sein |
| 6 | H2 embedded Default, PostgreSQL optional | Immer PostgreSQL | Lokaler Betrieb ohne Zusatzinstallation; Docker nutzt PostgreSQL |
| 7 | Cache in Metadaten-DB + Caffeine | Redis; nur In-Memory | Persistenz über Neustart, Mehrinstanz ohne weitere Komponente |
| 8 | Thymeleaf + htmx | React/Vue-SPA | Kein Node-Build, ein Artefakt, geringe Komplexität für Admin-Oberfläche |
| 9 | Anonymer Zugang als konfigurierter Service-User (`jdbcodata.odata.anonymous-user`) | Eigener „öffentlich“-Schalter pro Query; CORS + Anonymous nur für `$metadata` | Nutzt Freigaben, hinterlegte Credentials und Protokollierung der Service-User unverändert; standardmäßig aus, für öffentliche Demo-Feeds und OData-Explorer ohne Login-Dialog |
