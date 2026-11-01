# JdbcOdata – Project Requirement Document (PRD)

Stand: 2026-10-16 · Version 1.0 · Status: Release 1.0 umgesetzt (M1 bis M4)
Architektur: siehe [ARCHITECTURE.md](ARCHITECTURE.md)

## 1. Ziel und Hintergrund

**Problem.** Fachanwender benötigen Daten aus mehreren firmeninternen Datenbanken (Oracle, PostgreSQL, MySQL/MariaDB) in Excel und PowerBI. Heute entstehen dafür Einzellösungen (ODBC-Verbindungen pro Rechner, kopierte SQL-Skripte, manuelle Exporte), die schwer wartbar sind, Produktions-DBs unnötig belasten und keine Übersicht bieten, wer welche Daten abruft.

**Lösung.** Ein zentraler Service stellt vom Admin gepflegte SQL-Abfragen als OData-V4-Feeds bereit. Excel und PowerBI binden Feeds über ihren Standard-Connector ein. Jede Abfrage läuft unter den DB-Berechtigungen des anfragenden Users, Ergebnisse werden pro User gecacht, jede Ausführung wird protokolliert.

**Zielgruppen**

| Rolle | Bedürfnis |
|---|---|
| Fachanwender (Excel, PowerBI) | Feed-URL eintragen, eigene DB-Credentials eingeben, Daten aktualisieren |
| Admin (Data/BI-Team) | Abfragen anlegen, testen, freigeben, Cache steuern, Nutzung auswerten |
| Betrieb (IT) | Installation auf Linux/Windows oder Docker, TLS, Monitoring, Backup |
| Datenschutz / Security | Nachvollziehbarkeit, keine Passwortspeicherung im Standardfall, Verschlüsselung |

**Erfolgskriterien**

- Eine neue Abfrage ist ohne Neustart und ohne Entwickler in unter 10 Minuten in Excel verfügbar.
- Wiederholte Abrufe derselben Abfrage durch denselben User erzeugen innerhalb der TTL keine DB-Last.
- Für jede Ausführung ist nachvollziehbar: wer, wann, welche Query, Dauer, Zeilen, Status.
- Ein Betrieb kann den Service mit `docker compose up` oder `java -jar` in Betrieb nehmen.

## 2. Scope

**In Scope (Phase 1)**

- OData-V4-Feed (lesend) für Excel 365 und PowerBI Desktop/Service (über Gateway)
- Dynamische Verwaltung von Abfragen und Datenquellen über Web-UI und REST-API
- Identität: Basic-Auth-Pass-Through, optionale Service-User mit hinterlegten DB-Credentials, `FIXED`-Modus mit technischem Account pro Query
- Ergebnis-Cache pro User mit TTL und Invalidierung
- Ausführungsstatistik und Audit-Log mit UI-Auswertung und Export
- Native Ausführung auf Linux und Windows, Docker-Compose-Umgebung
- Datenbanken: Oracle, PostgreSQL, MySQL/MariaDB; weitere per JDBC-Treiber in `lib/`

**Out of Scope (Phase 1)**

- Schreibende Operationen über OData
- SSO für Feeds (Kerberos, OIDC, Entra ID). Die Admin-UI unterstützt OIDC seit 1.1.0
- OData V2 / Atom
- Mandantenfähigkeit
- Redis oder anderer externer Cache
- Zeitgesteuertes Vorwärmen des Caches

## 3. Personas und Use Cases

**Personas**

- *Maria, Controllerin*: nutzt Excel, kennt ihre Oracle-Zugangsdaten, will morgens „Aktualisieren“ drücken.
- *Tom, BI-Entwickler (Admin)*: schreibt SQL, kennt die Datenquellen, pflegt Freigaben.
- *Sven, Betrieb*: installiert und überwacht, hat keinen Bezug zu den Fachdaten.

**Use Cases**

| ID | Titel | Akteur | Ablauf (Kurzform) | Ergebnis |
|---|---|---|---|---|
| UC-01 | Feed in Excel einbinden | Fachanwender | Daten abrufen → Aus OData-Feed → URL `https://host/odata/` → Basic Auth mit DB-Credentials → Navigator zeigt EntitySets → Laden | Tabelle in Excel, aktualisierbar |
| UC-02 | Feed in PowerBI einbinden | Fachanwender | Wie UC-01 über PowerBI-OData-Connector; im PowerBI Service über Gateway mit Basic Auth | Dataset mit Scheduled Refresh |
| UC-03 | Abfrage anlegen | Admin | UI → Neue Query → Name, Datenquelle, SQL, Parameter → Testlauf mit eigenen Credentials → Schema-Snapshot → Speichern | EntitySet sofort im Service-Dokument |
| UC-04 | Abfrage bearbeiten | Admin | Query öffnen → SQL ändern → Testlauf → Speichern | Neue Version, Cache der Query invalidiert, Historie erhalten |
| UC-05 | Abfrage löschen | Admin | Query → Löschen → Bestätigen | EntitySet verschwindet, Cache gelöscht, Log bleibt |
| UC-06 | Abfrage unter technischem User freigeben | Admin | Query → Modus `FIXED` → Account wählen → Freigabeliste pflegen | Freigegebene User sehen die Query, DB-Zugriff über technischen Account |
| UC-07 | DB-Credentials hinterlegen | Admin / Service-User | Service-User → Datenquelle → DB-User/Passwort hinterlegen | User meldet sich in Excel mit Service-Login an, Service nutzt hinterlegte DB-Credentials |
| UC-08 | Cache invalidieren | Admin | Cache-Seite → Query (optional User) → Invalidieren | Nächster Abruf trifft DB |
| UC-09 | Cache umgehen | Fachanwender | Feed-URL mit `?refresh=true` | Frische Daten, Cache wird neu befüllt |
| UC-10 | Statistik auswerten | Admin | Statistik-Seite → Zeitraum, Query, User filtern → Diagramme, Tabelle, CSV | Nutzung, Laufzeiten, Fehler sichtbar |
| UC-11 | Installation nativ | Betrieb | Paket entpacken, `config/application.yml` anpassen, Master-Key setzen, `bin/jdbcodata.sh` bzw. `.cmd` | Service läuft, Admin-Login möglich |
| UC-12 | Installation Docker | Betrieb | `.env` befüllen, `docker compose up -d` | Service mit TLS und PostgreSQL läuft |
| UC-13 | Datenquelle anlegen | Admin | UI → Datenquellen → JDBC-URL, Treiber, Limits → Verbindungstest mit Test-Credentials | Datenquelle für Queries wählbar |

## 4. Funktionale Anforderungen

Priorität: **MUST** (Phase 1 zwingend), **SHOULD** (Phase 1 angestrebt), **COULD** (Phase 2).

### 4.1 OData-Feed (FR-01 … FR-09)

| ID | Anforderung | Prio |
|---|---|---|
| FR-01 | Der Service liefert unter `/odata/` ein OData-V4-Service-Dokument und unter `/odata/$metadata` ein CSDL-Dokument, die Excel 365 und PowerBI ohne Anpassung lesen können. | MUST |
| FR-02 | Jede aktive, für den Aufrufer sichtbare Query erscheint als EntitySet mit dem vom Admin vergebenen Namen. | MUST |
| FR-03 | `GET /odata/{EntitySet}` liefert die Ergebniszeilen als OData-JSON (`odata.metadata=minimal`), gestreamt. | MUST |
| FR-04 | Die Query-Optionen `$top`, `$skip`, `$filter`, `$select`, `$orderby`, `$count` werden unterstützt und auf dem Gesamtergebnis ausgewertet. Nicht unterstützte Optionen (`$expand`, `$search`, `$apply`) liefern `501`. | MUST |
| FR-05 | Query-Parameter werden als Custom Query Options (`?name=wert`) übergeben; Pflichtparameter ohne Wert und Default liefern `400` mit verständlicher Meldung. | MUST |
| FR-06 | Jeder EntityType hat eine Key-Property: synthetisches `RowId` (Int64) oder vom Admin markierte Spalten. | MUST |
| FR-07 | JDBC-Typen werden nach der Tabelle in ARCHITECTURE.md §4 auf EDM-Typen abgebildet; unbekannte Typen werden als String geliefert und im Snapshot markiert. | MUST |
| FR-08 | Fehlerantworten folgen dem OData-Fehlerformat und enthalten nie SQL-Text oder Credentials. Fehlgeschlagene DB-Authentifizierung liefert `401` mit `WWW-Authenticate: Basic`. | MUST |
| FR-09 | Ergebnisse über dem konfigurierten Zeilen- oder Byte-Limit werden mit `413` und Hinweis auf Parameter/`$top` abgelehnt. | SHOULD |

### 4.2 Query-Verwaltung (FR-10 … FR-19)

| ID | Anforderung | Prio |
|---|---|---|
| FR-10 | Admins können Queries anlegen, bearbeiten, deaktivieren und löschen; Änderungen wirken ohne Neustart. | MUST |
| FR-11 | Eine Query hat: Name (eindeutig, `[A-Za-z][A-Za-z0-9_]*`), Beschreibung, Datenquelle, SQL, Parameterliste, Ausführungsmodus, Cache-Einstellungen, Key-Spalten, Status. | MUST |
| FR-12 | SQL wird beim Speichern validiert: syntaktisch parsebar, nur `SELECT`/`WITH`, alle `:parameter` deklariert, keine undeklarierten Platzhalter. | MUST |
| FR-13 | Testlauf: Admin führt die Query mit eigenen Credentials und Parameterwerten aus und sieht Vorschau (max. 100 Zeilen), Dauer und Spaltentypen. | MUST |
| FR-14 | Schema-Snapshot: beim Speichern/Testen werden Spaltenmetadaten persistiert; Admin kann den Snapshot manuell erneuern; Abweichungen zum vorherigen Snapshot werden angezeigt. | MUST |
| FR-15 | Jede Änderung erzeugt eine neue Version; die Historie (SQL, Parameter, Autor, Zeit) ist einsehbar; eine ältere Version kann wiederhergestellt werden. | SHOULD |
| FR-16 | Parameter haben Name, Typ (`STRING`, `INT`, `DECIMAL`, `DATE`, `TIMESTAMP`, `BOOLEAN`), Pflicht-Flag, Default, Beschreibung, Flag „sensitiv“ (maskiert im Log). | MUST |
| FR-17 | Spalten des Snapshots können ausgeblendet werden (z.B. BLOB, interne IDs). | SHOULD |
| FR-18 | Queries können per REST-API exportiert und importiert werden (JSON), um sie zwischen Umgebungen zu übertragen. | COULD |
| FR-19 | Query-Limits (Timeout, Max-Zeilen) sind pro Query setzbar, dürfen die Datenquellen-Limits aber nur unterschreiten. | MUST |

### 4.3 Datenquellen (FR-20 … FR-25)

| ID | Anforderung | Prio |
|---|---|---|
| FR-20 | Admins verwalten Datenquellen mit Name, JDBC-URL, Treiberklasse, Zeitzone, Standard-Timeout, Max-Zeilen, Pool-Einstellungen, Limit gleichzeitiger Ausführungen. | MUST |
| FR-21 | Verbindungstest mit vom Admin eingegebenen Credentials (werden nicht gespeichert). | MUST |
| FR-22 | Treiber für Oracle, PostgreSQL, MySQL/MariaDB sind gebündelt; weitere JAR-Treiber aus `lib/` werden beim Start geladen. | MUST |
| FR-23 | Eine Datenquelle kann nur gelöscht werden, wenn keine Query sie referenziert. | MUST |
| FR-24 | Pro Datenquelle sind JDBC-Properties (z.B. `oracle.net.CONNECT_TIMEOUT`) als Schlüssel-Wert-Paare setzbar. | SHOULD |
| FR-25 | Datenquellen können deaktiviert werden; zugehörige Queries verschwinden dann aus dem Service-Dokument. | SHOULD |

### 4.4 Identität und Berechtigungen (FR-30 … FR-39)

| ID | Anforderung | Prio |
|---|---|---|
| FR-30 | OData-Endpunkte erfordern HTTP Basic Auth; ohne Credentials antwortet der Service `401` mit `WWW-Authenticate: Basic realm="JdbcOdata"`. | MUST |
| FR-31 | Pass-Through: existiert kein Service-User mit dem Namen, werden Username/Passwort unverändert als DB-Login der Query-Datenquelle verwendet und nicht persistiert. | MUST |
| FR-32 | Service-User: Admins können Service-User mit eigenem Passwort anlegen und pro Datenquelle DB-Credentials hinterlegen; beim Aufruf wird das Service-Passwort geprüft und die hinterlegten DB-Credentials verwendet; fehlen sie, greift Pass-Through. | MUST |
| FR-32a | PowerBI Service über On-Premises Data Gateway wird ausschließlich mit Service-Usern betrieben, die hinterlegte DB-Credentials verwenden; persönliche DB-Passwörter dürfen nicht im Gateway gespeichert werden. Die Admin-Doku und die Feed-Anleitung in der UI (FR-67) weisen darauf hin. | MUST |
| FR-32b | Service-User-Namen beginnen mit dem Präfix `svc_`; die UI schlägt das Präfix vor und warnt bei Abweichung, damit Service-User nicht mit DB-Usernamen kollidieren. | MUST |
| FR-32c | Ein Service-User kann mit dem Flag „Pass-Through erzwingen“ markiert werden; dann werden für ihn immer die Basic-Auth-Credentials als DB-Login verwendet und hinterlegte Credentials ignoriert. | SHOULD |
| FR-33 | Ausführungsmodus `CALLER` (Standard) oder `FIXED` pro Query; bei `FIXED` läuft die Query unter einem hinterlegten technischen DB-Account. | MUST |
| FR-34 | Für `FIXED`-Queries pflegt der Admin eine Freigabeliste (Service-User, Gruppen oder „alle authentifizierten“); nicht freigegebene Aufrufer sehen die Query nicht (`404`). | MUST |
| FR-35 | Service-Dokument und `$metadata` sind personalisiert: sie enthalten nur Queries, die der Aufrufer sehen darf. | MUST |
| FR-36 | Hinterlegte und technische Credentials werden mit AES-256-GCM verschlüsselt; Master-Key aus Env-Var oder Keystore; Key-Rotation über Key-ID ist möglich. | MUST |
| FR-37 | Fehlversuche bei Basic Auth werden pro IP und Username limitiert (Default 20/min), danach `429`. | SHOULD |
| FR-38 | Service-User können über die UI ihr eigenes Service-Passwort und ihre hinterlegten DB-Credentials ändern (Self-Service-Seite). | COULD |
| FR-39 | Gruppen für Service-User zur einfacheren Freigabe. | SHOULD |

### 4.5 Caching (FR-40 … FR-48)

| ID | Anforderung | Prio |
|---|---|---|
| FR-40 | Cache-Key ist `(Query, Query-Version, effektiver DB-User, Parameterwerte)`; ein User erhält nie Ergebnisse eines anderen DB-Users. | MUST |
| FR-41 | Pro Query: Cache an/aus, TTL in Sekunden, Max-Zeilen im Cache. | MUST |
| FR-42 | Cache-Einträge überleben einen Neustart (persistent in der Metadaten-DB). | MUST |
| FR-43 | Invalidierung: manuell per UI/API pro Query oder Query+User; automatisch bei Query-Änderung; per Request mit `?refresh=true`. | MUST |
| FR-44 | Antworten tragen `X-Cache: HIT|MISS|BYPASS|DISABLED` und bei HIT `X-Cache-Age`. | SHOULD |
| FR-45 | Parallele Abrufe desselben Keys lösen nur eine DB-Ausführung aus (Stampede-Schutz). | SHOULD |
| FR-46 | Gesamtgröße des Caches ist begrenzt (konfigurierbar); bei Überschreitung werden älteste Einträge entfernt. | MUST |
| FR-47 | Cache-Seite in der UI zeigt Einträge mit Query, User, Alter, Zeilen, Größe. | SHOULD |
| FR-48 | Zeitgesteuertes Vorwärmen des Caches für `FIXED`-Queries. | COULD |

### 4.6 Statistiken und Protokollierung (FR-50 … FR-58)

| ID | Anforderung | Prio |
|---|---|---|
| FR-50 | Jede OData-Ausführung wird mit den Feldern aus ARCHITECTURE.md §7 protokolliert (Aufrufer, DB-User, Query, Client, Parameter, Cache-Status, DB-Dauer, Gesamtdauer, Zeilen, Status, Fehler). | MUST |
| FR-51 | Protokollierung ist asynchron und verzögert die Antwort nicht; bei Ausfall der Metadaten-DB wird gepuffert und gewarnt. | SHOULD |
| FR-52 | Sensitiv markierte Parameter und Passwörter erscheinen nie im Protokoll. | MUST |
| FR-53 | Statistik-UI: Top-Queries, langsamste Queries (p50/p95), Fehlerquote, Nutzung pro User, Cache-Trefferquote, Zeitverlauf; Filter nach Zeitraum, Query, User, Datenquelle, Status. | MUST |
| FR-54 | Rohdaten-Export als CSV über UI und API. | MUST |
| FR-55 | Retention konfigurierbar (Default 90 Tage); Tagesaggregate bleiben länger erhalten (Default 2 Jahre). | SHOULD |
| FR-56 | Audit-Log für Änderungen an Queries, Datenquellen, Usern, Credentials sowie Logins und Query-Testläufe (wer, wann, was). | MUST |
| FR-57 | Micrometer-Metriken (Ausführungen, Dauer, Fehler, Cache-Hits) unter `/actuator/prometheus`, optional aktivierbar. | SHOULD |
| FR-58 | Client-Erkennung aus `User-Agent` (`EXCEL`, `POWERBI`, `OTHER`). | SHOULD |

### 4.7 Admin-UI und REST-API (FR-60 … FR-68)

| ID | Anforderung | Prio |
|---|---|---|
| FR-60 | Web-UI unter `/admin` mit Login, Seiten Datenquellen, Queries, Service-User, Cache, Statistiken, Einstellungen. | MUST |
| FR-61 | SQL-Editor mit Syntax-Hervorhebung, Parameter-Erkennung und Testlauf-Vorschau. | MUST |
| FR-62 | REST-API unter `/api/v1` mit OpenAPI-Beschreibung; alle UI-Funktionen sind über die API erreichbar. | MUST |
| FR-63 | Admin-Rollen `ADMIN` (alles) und `VIEWER` (lesen, Statistiken). | MUST |
| FR-64 | API-Token (Bearer) für Automatisierung, pro Admin erzeugbar und widerrufbar. | SHOULD |
| FR-65 | Initial-Admin wird beim ersten Start aus Env-Vars angelegt. | MUST |
| FR-66 | UI ist auf Deutsch und Englisch verfügbar (i18n). | COULD |
| FR-67 | Query-Seite zeigt die fertige Feed-URL zum Kopieren und eine Anleitung für Excel/PowerBI. | SHOULD |
| FR-68 | Health-Endpunkt `/actuator/health` ohne Auth, Details nur für Admins. | MUST |

## 5. Nicht-funktionale Anforderungen

| ID | Kategorie | Anforderung | Prio |
|---|---|---|---|
| NFR-01 | Sicherheit | Alle Endpunkte nur über HTTPS; Klartext-HTTP nur mit expliziter Flag und Log-Warnung. | MUST |
| NFR-02 | Sicherheit | DB-Connections sind read-only; SQL wird auf `SELECT`/`WITH` beschränkt; Parameter werden nur gebunden. | MUST |
| NFR-03 | Sicherheit | Pass-Through-Passwörter werden nie persistiert und nie geloggt; im Prozess nur Request-Scope und als Hash im Pool-Key. | MUST |
| NFR-04 | Sicherheit | Secrets at rest AES-256-GCM; Admin-/Service-Passwörter bcrypt (Cost ≥ 12). | MUST |
| NFR-05 | Sicherheit | Abhängigkeiten werden im Build auf bekannte Schwachstellen geprüft (OWASP Dependency-Check oder vergleichbar). | SHOULD |
| NFR-10 | Performance | Cache-Hit: Time-to-first-byte < 200 ms bei 100 000 Zeilen auf Referenz-Hardware (4 vCPU, 4 GB RAM). | SHOULD |
| NFR-11 | Performance | Ergebnisse werden gestreamt; Speicherbedarf pro Request ist unabhängig von der Zeilenzahl bis zum Cache-Limit. | MUST |
| NFR-12 | Performance | Pro Datenquelle konfigurierbares Limit gleichzeitiger DB-Ausführungen; darüber `503` mit `Retry-After`. | MUST |
| NFR-13 | Performance | Ergebnisgrenze Default 100 000 Zeilen / 256 MB pro Abruf, konfigurierbar; die Byte-Grenze fehlt noch (O-15). | MUST |
| NFR-20 | Kompatibilität | Excel 365 (Windows) „Aus OData-Feed“; PowerBI Desktop und PowerBI Service über On-Premises Data Gateway mit Basic Auth (im Gateway nur Service-User, siehe FR-32a). | MUST |
| NFR-21 | Kompatibilität | Oracle 19c+, PostgreSQL 13+, MySQL 8+ / MariaDB 10.6+. | MUST |
| NFR-30 | Portabilität | Läuft auf Linux (x86_64, glibc) und Windows 10/11 bzw. Server 2019+ mit Java 21; ein identisches JAR. | MUST |
| NFR-31 | Portabilität | Docker-Image (linux/amd64, linux/arm64) und `docker-compose.yml` mit PostgreSQL und TLS-Proxy. | MUST |
| NFR-32 | Portabilität | Keine Pfade, Zeilenenden oder Zeichensätze hart kodiert; UTF-8 durchgängig. | MUST |
| NFR-40 | Betrieb | Konfiguration über `application.yml` und Env-Vars mit Präfix `JDBCODATA_`; alle Defaults dokumentiert. | MUST |
| NFR-41 | Betrieb | Strukturierte Logs (Text oder JSON), Log-Level zur Laufzeit änderbar. | SHOULD |
| NFR-42 | Betrieb | Flyway-Migrationen; Upgrade = neues JAR/Image starten. | MUST |
| NFR-43 | Betrieb | Backup = Metadaten-DB (H2-Datei oder PostgreSQL-Dump) + `config/`; dokumentiert. | MUST |
| NFR-44 | Verfügbarkeit | Mehrere Instanzen hinter Load-Balancer mit gemeinsamer PostgreSQL-Metadaten-DB sind möglich (keine lokale Zustandsabhängigkeit außer Caffeine). | SHOULD |
| NFR-50 | Wartbarkeit | Unit-Tests für Identitätsauflösung, Typmapping, OData-Optionsauswertung, Cache-Key; Integrationstests mit Testcontainers (PostgreSQL, MariaDB, Oracle Free). | MUST |
| NFR-51 | Wartbarkeit | CI-Pipeline: Build, Tests, Dependency-Check, Docker-Image. | SHOULD |
| NFR-52 | Wartbarkeit | Code auf Englisch, Dokumentation für Admins und Betrieb auf Deutsch. | MUST |
| NFR-60 | Datenschutz | Protokolldaten enthalten nur, was für Betrieb und Audit nötig ist; Retention durchsetzbar; Export nur für `ADMIN`. | MUST |

## 6. Datenmodell

Alle Tabellen in der Metadaten-DB; IDs sind UUIDs; Zeitstempel in UTC.

**datasource**
`id, name (unique), description, jdbc_url, driver_class, jdbc_properties (json), timezone, default_timeout_sec, max_rows, max_concurrent, pool_size, enabled, created_at, updated_at`

**query_definition**
`id, name (unique), description, datasource_id (FK), sql_text, execution_mode (CALLER|FIXED), fixed_credential_id (FK, nullable), cache_enabled, cache_ttl_sec, cache_max_rows, timeout_sec, max_rows, key_mode (ROWID|COLUMNS), version, enabled, created_by, created_at, updated_by, updated_at`

**query_version**
`id, query_id (FK), version, sql_text, parameters (json), columns (json), changed_by, changed_at, comment`

**query_parameter**
`id, query_id (FK), name, type, required, default_value, description, sensitive, position`

**query_column**
`id, query_id (FK), position, column_name, jdbc_type, jdbc_type_name, edm_type, precision, scale, nullable, is_key, visible`

**query_grant**
`id, query_id (FK), principal_type (USER|GROUP|ALL_AUTHENTICATED), principal_id, granted_by, granted_at`

**service_user**
`id, username (unique, Präfix svc_), password_hash, display_name, force_passthrough, enabled, created_at, last_login_at`

**service_group** / **service_group_member**
`id, name` / `group_id, user_id`

**stored_credential**
`id, service_user_id (FK), datasource_id (FK), db_username, db_password_enc, key_id, updated_at` – unique (service_user_id, datasource_id)

**fixed_credential**
`id, name, datasource_id (FK), db_username, db_password_enc, key_id, updated_at`

**admin_user**
`id, username (unique), password_hash, role (ADMIN|VIEWER), api_token_hash, enabled, created_at, last_login_at`

**cache_entry**
`id, query_id (FK), query_version, db_user, params_hash, params_json, column_blob (bytea, gzip), row_count, byte_size, created_at, expires_at, last_access_at` – unique (query_id, query_version, db_user, params_hash)

**execution_log**
`id, started_at, finished_at, query_id, query_name, query_version, datasource_id, caller, effective_db_user, execution_mode, client, user_agent, client_ip, parameters (json), odata_options, cache_status, db_duration_ms, total_duration_ms, row_count, bytes_sent, status, error_message`

**execution_daily**
`day, query_id, caller, executions, cache_hits, errors, rows_total, db_ms_total, total_ms_total` – PK (day, query_id, caller)

**audit_log**
`id, at, admin_user, action, entity_type, entity_id, diff (json)`

## 7. API-Spezifikation

### 7.1 OData

| Methode | Pfad | Beschreibung |
|---|---|---|
| GET | `/odata/` | Service-Dokument (personalisiert) |
| GET | `/odata/$metadata` | CSDL (personalisiert) |
| GET | `/odata/{EntitySet}` | Daten; Query-Optionen `$top,$skip,$filter,$select,$orderby,$count`; Custom-Optionen für Parameter; `refresh=true` umgeht Cache |
| GET | `/odata/{EntitySet}/$count` | Anzahl Zeilen |

Antwort-Header: `OData-Version: 4.0`, `X-Cache`, `X-Cache-Age`, `X-Query-Version`.

### 7.2 REST-API `/api/v1`

| Methode | Pfad | Rolle | Beschreibung |
|---|---|---|---|
| GET/POST | `/datasources` | ADMIN (GET: VIEWER) | Liste / Anlegen |
| GET/PUT/DELETE | `/datasources/{id}` | ADMIN | Lesen / Ändern / Löschen |
| POST | `/datasources/{id}/test` | ADMIN | Body: `{username,password}` → `{ok, productName, version, durationMs}` |
| GET/POST | `/queries` | ADMIN (GET: VIEWER) | Liste / Anlegen |
| GET/PUT/DELETE | `/queries/{id}` | ADMIN | Lesen / Ändern (neue Version) / Löschen |
| POST | `/queries/{id}/test` | ADMIN | Body: `{username,password,parameters}` → Vorschau, Spalten, Dauer |
| POST | `/queries/{id}/snapshot` | ADMIN | Schema-Snapshot erneuern |
| GET | `/queries/{id}/versions` | VIEWER | Historie |
| POST | `/queries/{id}/versions/{v}/restore` | ADMIN | Version wiederherstellen |
| GET/PUT | `/queries/{id}/grants` | ADMIN | Freigaben |
| GET/POST | `/users` | ADMIN | Service-User |
| GET/PUT/DELETE | `/users/{id}` | ADMIN | |
| PUT/DELETE | `/users/{id}/credentials/{datasourceId}` | ADMIN | DB-Credentials hinterlegen / entfernen |
| GET/POST, PUT/DELETE | `/fixed-credentials`, `/fixed-credentials/{id}` | ADMIN | Technische Accounts |
| GET | `/cache` | VIEWER | Einträge (Filter: queryId, dbUser) |
| DELETE | `/cache` | ADMIN | Invalidieren (Filter: queryId, dbUser) |
| GET | `/stats/summary?from&to` | VIEWER | Kennzahlen |
| GET | `/stats/executions?from&to&queryId&caller&status&page` | VIEWER | Rohdaten |
| GET | `/stats/export.csv?…` | ADMIN | CSV |
| GET | `/audit?from&to` | ADMIN | Audit-Log |
| GET/POST/DELETE | `/admins`, `/admins/{id}`, `/admins/{id}/token` | ADMIN | Admin-Konten, API-Token |

Payload-Skizze Query (POST/PUT):

```json
{
  "name": "Umsatz",
  "description": "Umsatz je Kunde und Monat",
  "datasourceId": "…",
  "sql": "SELECT kunde, monat, betrag FROM v_umsatz WHERE jahr = :p_jahr",
  "parameters": [
    { "name": "p_jahr", "type": "INT", "required": true, "default": null, "sensitive": false }
  ],
  "executionMode": "CALLER",
  "fixedCredentialId": null,
  "cache": { "enabled": true, "ttlSeconds": 3600, "maxRows": 200000 },
  "limits": { "timeoutSeconds": 60, "maxRows": 200000 },
  "keyMode": "ROWID",
  "keyColumns": [],
  "hiddenColumns": [],
  "enabled": true
}
```

## 8. Sicherheitskonzept

| Bedrohung | Gegenmaßnahme |
|---|---|
| Abfangen von Basic-Auth-Credentials | TLS-Pflicht (NFR-01), HSTS, Klartext nur mit Flag |
| Leak gespeicherter DB-Passwörter | AES-256-GCM, Master-Key außerhalb der DB, Pass-Through speichert nichts |
| SQL-Injection über Parameter | Nur `PreparedStatement`, Typprüfung der Parameter, SQL nur von Admins |
| Schreibzugriff über manipulierte Query | JSqlParser-Whitelist, `readOnly`-Connection, Rollback |
| Cache-Leak zwischen Usern | DB-User im Cache-Key (FR-40), Tests dafür verpflichtend |
| Brute-Force auf Basic Auth | Rate-Limiting pro IP/User (FR-37), Fehlversuche im Log |
| Existenz-Enumeration von Queries | `404` statt `403`, personalisiertes Service-Dokument |
| Rechteausweitung durch `FIXED`-Queries | Explizite Freigabeliste, Audit-Log, Kennzeichnung in der UI |
| DoS durch große Abfragen | Timeout, Max-Zeilen, Concurrency-Limit pro Datenquelle, Streaming |
| Admin-Konto kompromittiert | bcrypt, Session-Timeout, API-Token widerrufbar, Audit-Log |
| Verwundbare Abhängigkeiten | Dependency-Check im Build (NFR-05), Non-Root-Container |
| Log-Leaks | Keine Passwörter, keine SQL mit Werten, sensitiv markierte Parameter maskiert |

## 9. Deployment und Betrieb

**Native Installation**

1. Java 21 installieren (oder Paket mit gebündelter JRE verwenden).
2. Archiv entpacken, `config/application.yml` anpassen (Port, TLS, Metadaten-DB).
3. `JDBCODATA_MASTER_KEY` (32 Byte Base64), `JDBCODATA_ADMIN_USER`, `JDBCODATA_ADMIN_PASSWORD` setzen.
4. `bin/jdbcodata.sh` bzw. `bin/jdbcodata.cmd` starten; als Dienst: systemd-Unit bzw. WinSW-Vorlage aus `contrib/`.
5. `https://host:8443/admin` öffnen, Datenquelle anlegen, erste Query testen.

**Docker**

1. `.env` aus `.env.example` befüllen (Master-Key, Admin, PostgreSQL-Passwort, Hostname für TLS).
2. `docker compose up -d` startet `caddy` (TLS, automatisch oder mit eigenem Zertifikat), `jdbcodata`, `postgres`.
3. Zusätzliche JDBC-Treiber über Volume `./lib:/app/lib`.
4. Update: neue Version in `.env`, `docker compose up -d --build`; Flyway migriert automatisch.

**PowerBI Service / Gateway.** Datasets, die im PowerBI Service über das On-Premises Data Gateway aktualisiert werden, verwenden im Gateway einen Service-User (`svc_…`) mit hinterlegten DB-Credentials. Persönliche DB-Konten werden nicht im Gateway hinterlegt. Für Reports, die Row-Level-Security der DB brauchen, bleibt PowerBI Desktop mit persönlichem Login der Weg.

**Backup.** Metadaten-DB (H2-Datei bei gestopptem Service oder `pg_dump`) plus `config/` und `certs/`. Der Cache muss nicht gesichert werden.

**Monitoring.** `/actuator/health` für Liveness/Readiness, optional Prometheus-Metriken, Logs nach stdout (Docker) bzw. `data/logs/` (nativ).

**Konfigurationsreferenz.** Siehe ARCHITECTURE.md §10; vollständige Liste wird mit M1 als `docs/CONFIG.md` generiert.

## 10. Teststrategie

| Ebene | Inhalt | Werkzeug |
|---|---|---|
| Unit | Identitätsauflösung (alle Pfade aus ARCHITECTURE.md §5), Typmapping, OData-Optionsauswertung (`$filter`-Ausdrücke, `$orderby` mit Null-Werten, `$select`), Cache-Key-Normalisierung, SQL-Validator, Verschlüsselung | JUnit 5, AssertJ |
| Integration | Query-Ausführung gegen echte DBs, Schema-Snapshot, Pass-Through-Login, Fehlerpfade (falsches Passwort, Timeout), Cache-Persistenz, Flyway auf H2 und PostgreSQL | Testcontainers: `postgres`, `mariadb`, `gvenzl/oracle-free` |
| OData-Konformität | Service-Dokument, `$metadata`, Query-Optionen, Fehlerformat | Olingo-Client, Schema-Validierung des CSDL |
| Sicherheit | Cache-Isolation zwischen Usern, `404` für nicht freigegebene Queries, keine Secrets in Logs, Rate-Limit | Integrationstests + Log-Assertions |
| Last | 50 parallele Excel-Refreshes auf gecachte Query mit 100 000 Zeilen; Concurrency-Limit greift | Gatling oder k6 |
| Abnahme (manuell) | UC-01 bis UC-13 in Excel 365 und PowerBI Desktop; Scheduled Refresh über Gateway | Checkliste in `docs/ACCEPTANCE.md` |
| Plattform | Build und Smoke-Test auf Linux und Windows (CI-Matrix), Docker-Image auf amd64/arm64 | GitHub Actions oder GitLab CI |

## 11. Umsetzungsphasen

| Meilenstein | Inhalt | Ergebnis |
|---|---|---|
| **M1 – Skeleton** ✅ 2026-10-16 | Maven-Projekt, Spring Boot 4, Flyway + H2, Datenquellen-CRUD (API), Queries aus YAML-Seed mit Schema-Snapshot, Olingo-Endpunkt mit Service-Dokument, `$metadata`, Daten (`$top`, `$skip`, `$select`, `$count`, Parameter), Basic-Auth-Pass-Through mit Pools pro Identität, Demo-Profil, Dockerfile, 66 Tests mit 94 % Zeilenabdeckung | Feed in Excel sichtbar |
| **M2 – Query-Verwaltung** ✅ 2026-10-16 | Query-CRUD über API und UI, SQL-Validierung mit JSqlParser (nur SELECT), Parameter-Editor, Testlauf mit Vorschau (auch für ungespeicherte Queries), Schema-Snapshot und Spalteneinstellungen (Schlüssel, sichtbar), Versionshistorie mit Wiederherstellen, `$filter` und `$orderby` in-memory, Admin-UI mit Login (Thymeleaf + htmx + CodeMirror), 128 Tests mit 94 % Zeilenabdeckung | Admin pflegt Queries ohne Neustart |
| **M3 – Identität, Cache, Statistik** ✅ 2026-10-16 | Service-User mit Gruppen und verschlüsselt hinterlegten DB-Credentials (AES-256-GCM, Master-Key aus Konfiguration oder Key-Datei), technische Accounts und `FIXED`-Modus mit Freigaben (User, Gruppe, alle), personalisiertes Service-Dokument, Flag „Pass-Through erzwingen“, persistenter Cache in der Metadaten-DB mit Caffeine-Schicht, Stampede-Schutz, `?refresh=true`, `X-Cache`-Header, Invalidierung über UI/API, Execution-Log mit Statistik-Seite (Kennzahlen, Top-Queries, Aufrufer, Verlauf, Rohdaten, CSV), Retention-Job, Audit-Log, Admin-Konten mit Rollen ADMIN/VIEWER in der DB, 147 Tests mit 94 % Zeilenabdeckung, Browser-Check mit Playwright | Alle MUST-Anforderungen bis auf TLS/Rate-Limiting (M4) erfüllt |
| **M4 – Betrieb und Härtung** ✅ 2026-10-16 | docker-compose mit PostgreSQL und Caddy (TLS intern, Let's Encrypt oder eigenes Zertifikat), TLS-Guard (Start ohne TLS nur mit Flag), Rate-Limiting pro IP und Benutzername (429), Concurrency-Limits pro Datenquelle (503 mit Retry-After), Prometheus-Metriken, Distributions-ZIP mit Start-Skripten für Linux und Windows, systemd- und WinSW-Vorlagen, Dokumentation (CONFIG, OPERATIONS, ADMIN_GUIDE, EXCEL_POWERBI, ACCEPTANCE), PostgreSQL-Integrationstest mit Testcontainers, Version 1.0.0 | Release 1.0 |
| **Phase 2 (Kandidaten)** | Cache-Vorwärmen, Gruppen aus LDAP, i18n, Import/Export | nach Bedarf |

## 12. Offene Punkte und Risiken

| # | Punkt | Auswirkung | Vorschlag |
|---|---|---|---|
| O-01 | Sehr große Ergebnismengen (> 100 000 Zeilen) | Speicher und Cache-Größe | **Entschieden 2026-10-16, angepasst 2026-10-30:** harte Grenze standardmäßig 100 000 Zeilen, per `jdbcodata.odata.max-rows` erhöhbar. Push-Down und Server-Paging sind umgesetzt |
| O-02 | PowerBI Service über Gateway: Basic Auth wird unterstützt, aber Credentials liegen im Gateway | Persönliche DB-Passwörter würden im Gateway gespeichert | **Entschieden 2026-10-16:** Für Gateway-Nutzung sind Service-User mit hinterlegten Credentials vorgeschrieben (FR-32a, §9) |
| O-03 | Oracle-Treiber-Lizenz beim Bundling | Proprietäre Lizenz im MIT-Projekt | **Entschieden 2026-10-30:** ojdbc11 wird nicht mitgeliefert. Betreiber legen den Treiber in `lib/` oder bauen mit `./mvnw -Poracle clean package` |
| O-04 | Zeitzonen bei `TIMESTAMP` ohne Zone | Falsche Uhrzeiten in Excel | Zeitzone pro Datenquelle konfigurierbar (FR-20), Default dokumentieren |
| O-05 | Excel-Query-Folding sendet teils `$filter` mit Funktionen, die Olingo in-memory nicht abbildet | `501` statt Daten | Unterstützte Funktionen dokumentieren; Excel fällt bei Fehlern auf lokale Filterung zurück, zu verifizieren in M2 |
| O-06 | Pass-Through-Pools halten Passwort-Hashes kurzzeitig im Speicher | Akzeptabel? | Mit Security abstimmen; Pool-Lebensdauer konfigurierbar |
| O-07 | Service-User-Name kollidiert mit DB-Username | Auflösung greift fälschlich den Service-User | **Entschieden 2026-10-16:** Namenskonvention mit Präfix `svc_` für Service-User (FR-32b); zusätzlich Flag „Pass-Through erzwingen“ (FR-32c) |
| O-08 | Mehrere Instanzen: Caffeine-Cache ist lokal, Invalidierung muss alle Instanzen erreichen | Veraltete Daten für max. TTL der Caffeine-Schicht | **Erledigt:** Generationszähler in der Metadaten-DB, geprüft mit `TwoNodeIT` |
| O-09 | Mehrinstanz-Betrieb mit H2 | Nicht möglich | Für Mehrinstanz PostgreSQL vorschreiben |
| O-10 | SQL-Validierung mit JSqlParser lehnt herstellerspezifische Syntax ab (z.B. Oracle-Hints in Sonderformen, exotische Funktionen) | Gültige Queries können nicht gespeichert werden | In M2 strikt umgesetzt (FR-12). Bei Bedarf pro Datenquelle ein Flag „nur lexikalische Prüfung“ ergänzen |
| O-11 | `$filter` wird in-memory nach dem vollständigen Laden ausgewertet | Excel-Query-Folding reduziert die DB-Last nicht | **Erledigt:** Push-Down pro Query schaltbar, siehe ARCHITECTURE.md |
| O-12 | Ohne konfigurierten Master-Key wird beim ersten Start eine Key-Datei unter `data/master.key` erzeugt | Verlust der Datei macht hinterlegte Credentials unlesbar | Für Produktion `JDBCODATA_MASTER_KEY` setzen oder die Datei sichern; Hinweis in Einstellungen und Log |
| O-13 | Tagesaggregate (`execution_daily`, FR-55) sind noch nicht umgesetzt; Statistiken rechnen direkt auf `execution_log` | Bei sehr vielen Datensätzen langsamere Statistik-Seite | Mit Retention von 90 Tagen unkritisch; Aggregate in Phase 2 |
| O-14 | Lasttest mit 50 parallelen Excel-Refreshes (Teststrategie §10) | Fand einen Speicherfehler bei Cache-Treffern | **Erledigt:** Skripte und Messwerte in `loadtest/`, Fehler mit `ResponseBudget` behoben |
| O-15 | NFR-11 (Streaming) und die Byte-Grenze aus NFR-13 sind nicht umgesetzt: Ergebnisse liegen vollständig im Speicher | Heap begrenzt große und parallele Abrufe | Stufe 2 der Enterprise-Roadmap |

## 13. Glossar

| Begriff | Bedeutung |
|---|---|
| OData | Open Data Protocol, REST-basiertes Protokoll für tabellarische Daten; Version 4 ist Standard in Excel/PowerBI |
| EntitySet / EntityType | OData-Begriffe für „Tabelle“ und „Zeilentyp“; hier eine Query und ihre Spalten |
| CSDL / `$metadata` | Schema-Beschreibung eines OData-Dienstes |
| Pass-Through | Die vom Client gesendeten Basic-Auth-Credentials werden direkt als DB-Login verwendet |
| Service-User | Konto im Service mit eigenem Passwort und optional hinterlegten DB-Credentials |
| Ausführungsmodus `CALLER` / `FIXED` | Query läuft unter Aufrufer-Identität bzw. unter technischem Account |
| Effektiver DB-User | DB-Identität, unter der eine Ausführung tatsächlich lief |
| Schema-Snapshot | Persistierte Spaltenmetadaten einer Query, Basis für `$metadata` |
| Metadaten-DB | Interne DB des Service (H2 oder PostgreSQL) für Konfiguration, Cache, Statistik |
| TTL | Time to live, Gültigkeitsdauer eines Cache-Eintrags |
| Master-Key | Schlüssel zur Verschlüsselung gespeicherter DB-Credentials |
