# Abnahme-Checkliste (Release 1.1)

Automatisch geprüft: `./mvnw verify` (Unit- und Integrationstests, 85 % Zeilenabdeckung), Tests gegen
echte PostgreSQL-, MariaDB-, MySQL- und Oracle-Server und gegen Keycloak (Testcontainers, laufen nur mit Docker),
Browser-Check `e2e/check.mjs`, Helm-Lint und -Template in der CI.

Manueller Lauf für 1.1.0 am 2026-11-04: Docker-Stack aus `docker/docker-compose.yml` (Caddy mit interner CA,
PostgreSQL als Metadaten-DB), dazu eine PostgreSQL-Datenbank mit 5000 Testzeilen. Die Schritte liefen per REST-API und
curl, die Admin-UI per `e2e/check.mjs` (alle Prüfungen bestanden). Ein Haken bedeutet: so ausgeführt und das Ergebnis
gesehen. Kein Haken bedeutet, dass der Lauf aussteht, nicht dass ein Fehler bekannt ist.

## Clients (nur mit Excel 365 und Power BI Desktop prüfbar)

| # | Use Case | Schritte | Erwartung | OK |
|---|---|---|---|---|
| UC-01 | Feed in Excel | Aus OData-Feed, `https://host/odata/`, DB-Login | Navigator zeigt Queries, Laden liefert Tabelle | ☐ |
| UC-02 | Feed in Power BI Desktop | OData-Feed, Standard-Anmeldung | Tabelle geladen, Filter werden gefoldet | ☐ |
| UC-14 | API-Key in Excel | Benutzer `apikey`, Key als Passwort | Daten des Service-Users laden | ☐ |

## Funktionen

| # | Use Case | Schritte | Erwartung | OK |
|---|---|---|---|---|
| UC-03 | Query anlegen | Query, Testlauf, Snapshot | EntitySet sofort im Feed | ☑ Testlauf 100 Zeilen, `Orders` im Service-Dokument, 5000 Zeilen |
| UC-04 | Query bearbeiten | SQL ändern, speichern, Snapshot erneuern | neue Version, Cache invalidiert | ☑ Version 2, neue Spalte im Feed, `X-Cache: MISS` |
| UC-05 | Query löschen | Löschen | EntitySet verschwindet, Protokoll bleibt | ☑ 204, nicht mehr im Feed, Ausführung weiter in der Statistik |
| UC-06 | FIXED mit Freigabe | technischer Account, Modus FIXED, Freigabe Service-User | nur freigegebener User sieht/lädt die Query, Protokoll zeigt technischen DB-User | ☑ `bob` 200, `carol` und Pass-Through 404, Protokoll `effectiveDbUser=tech` |
| UC-07 | Hinterlegte Credentials | Service-User mit DB-Zugangsdaten | Login mit Service-Passwort lädt Daten | ☑ 5000 Zeilen als `bob` |
| UC-08 | Cache invalidieren | Cache aktiv, zweimal laden, invalidieren | `X-Cache` HIT, nach Invalidierung MISS | ☑ |
| UC-09 | Cache umgehen | URL mit `?refresh=true` | `X-Cache: BYPASS` | ☑ |
| UC-10 | Statistik | Statistik, Filter, CSV | Ausführungen mit Dauer, Zeilen, Status sichtbar | ☑ API und CSV mit Dauer, Zeilen, Status und `bytes` |
| UC-13 | Datenquelle anlegen | Verbindungstest | Testergebnis mit DB-Produkt und Version | ☑ PostgreSQL 16.15 |
| UC-15 | API-Key | Key für Service-User anlegen, Feed laden, widerrufen | Feed 200, Admin-API 401, nach Widerruf 401 | ☑ |
| UC-16 | Erster Login | generiertes Admin-Passwort | Admin-UI leitet auf Passwortwechsel, API antwortet 403, danach 200 | ☑ |
| UC-17 | OIDC für die Admin-UI mit Entra ID | App-Registrierung, Rollen-Claim, Logout | Login mit Firmenkonto, Rolle aus dem Claim, Logout beim IdP | ☐ Keycloak automatisch (`KeycloakLoginIT`), Entra ID ausstehend |

## Installation und Betrieb

| # | Use Case | Schritte | Erwartung | OK |
|---|---|---|---|---|
| UC-11 | Native Installation | dist.zip, config, Start-Skript | Service läuft mit TLS, Admin-Login | ☑ Linux, eigener PKCS12-Keystore; Windows-Skript nicht geprüft |
| UC-12 | Docker | `.env`, `docker compose up -d` | HTTPS über Caddy, PostgreSQL als Metadaten-DB | ☑ HTTP leitet mit 308 auf HTTPS |
| UC-18 | Upgrade von 1.0.0 | 1.0.0 mit PostgreSQL und Daten, Image auf 1.1.0 tauschen | Migrationen laufen, Daten und Logins bleiben | ☑ 7 Migrationen, Feed und Admin-API 200, keine Fehler im Log |
| UC-19 | Zwei Instanzen | zweite Instanz auf derselben Metadaten-DB, Cache auf A invalidieren, an A anmelden | B liefert MISS, Sitzung gilt auf B, auch wenn A steht | ☑ MISS auf B nach 6 s, Sitzung mit gestopptem A gültig |
| UC-20 | Kubernetes | `helm install` in einem Cluster | Pods bereit, Probes grün, Feed erreichbar | ☐ nur Lint und Template in der CI |
| OPS-1 | Backup | Metadaten-DB und Master-Key sichern, Volumes löschen, neu einspielen | hinterlegte Credentials weiterhin nutzbar | ☑ `pg_dump`/`pg_restore`, Login mit hinterlegten Credentials 200 |
| OPS-2 | Monitoring | `/actuator/health`, `/actuator/prometheus` | UP, Metriken vorhanden | ☑ Prometheus ohne Login 401, mit Admin 27 `jdbcodata_*`-Metriken |
| OPS-3 | Datenbank fällt aus | Daten-DB stoppen, wiederholt abrufen | nach 5 Fehlversuchen sofort 503 mit `Retry-After`, Cache-Treffer laufen | ☑ 4 × 500 nach 15 s (der fünfte Fehler kam vom Health-Check), danach 503 in 20 ms, Metrik `unreachable` 1 |
| OPS-4 | Großer Export | Lasttest `loadtest/run.sh` | Heap bleibt stabil über `max-rows` | ☑ Messwerte in `docs/OPERATIONS.md` (#52) |
| OPS-5 | Tracing | OTLP-Endpunkt setzen, Feed abrufen | Trace mit DB-Span, keine Probes und Jobs | ☑ gegen Jaeger (#53); Tempo und Collector mit Auth ausstehend |
| OPS-6 | Release-Signatur | `cosign verify` für Image und `SHA256SUMS` | Signatur des Release-Workflows | ☑ 1.1.0: `SHA256SUMS` signiert, Prüfsummen stimmen; Image (amd64, arm64) mit cosign 3.1.4 geprüft |

## Sicherheit

| # | Use Case | Schritte | Erwartung | OK |
|---|---|---|---|---|
| SEC-1 | Klartext-HTTP | ohne TLS und ohne Flag starten | Start wird verweigert | ☑ `Refusing to start without TLS` |
| SEC-2 | Rate-Limit | 20 falsche Logins | 429 für eine Minute | ☑ ab dem 21. Versuch 429 mit `Retry-After: 60`, auch mit richtigem Passwort |
| SEC-3 | Enumeration | FIXED-Query ohne Freigabe aufrufen | 404, nicht 403 | ☑ |
