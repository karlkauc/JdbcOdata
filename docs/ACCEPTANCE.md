# Abnahme-Checkliste (Release 1.0)

Automatisch geprüft: `./mvnw verify` (Unit- und Integrationstests, 85 % Zeilenabdeckung), PostgreSQL-Test
mit Testcontainers (`PostgresMetadataIT`, läuft nur mit Docker), Browser-Check `e2e/check.mjs`.
Manuell mit Excel 365 und PowerBI Desktop abzunehmen:

| # | Use Case | Schritte | Erwartung | OK |
|---|---|---|---|---|
| UC-01 | Feed in Excel | Aus OData-Feed, `https://host/odata/`, DB-Login | Navigator zeigt Queries, Laden liefert Tabelle | ☐ |
| UC-02 | Feed in PowerBI Desktop | OData-Feed, Standard-Anmeldung | Tabelle geladen, Filter werden gefoldet | ☐ |
| UC-03 | Query anlegen | Admin-UI: Query, Testlauf, Snapshot | EntitySet sofort im Feed | ☐ |
| UC-04 | Query bearbeiten | SQL ändern, speichern, Snapshot erneuern | neue Version, Cache invalidiert | ☐ |
| UC-05 | Query löschen | Löschen | EntitySet verschwindet, Protokoll bleibt | ☐ |
| UC-06 | FIXED mit Freigabe | technischer Account, Modus FIXED, Freigabe Service-User | nur freigegebener User sieht/lädt die Query, Protokoll zeigt technischen DB-User | ☐ |
| UC-07 | Hinterlegte Credentials | Service-User mit DB-Zugangsdaten | Excel-Login mit Service-Passwort lädt Daten | ☐ |
| UC-08 | Cache invalidieren | Cache aktiv, zweimal laden, invalidieren | `X-Cache` HIT, nach Invalidierung MISS | ☐ |
| UC-09 | Cache umgehen | URL mit `?refresh=true` | `X-Cache: BYPASS` | ☐ |
| UC-10 | Statistik | Statistik-Seite, Filter, CSV | Ausführungen mit Dauer, Zeilen, Status sichtbar | ☐ |
| UC-11 | Native Installation | dist.zip, config, Start-Skript | Service läuft mit TLS, Admin-Login | ☐ |
| UC-12 | Docker | `.env`, `docker compose up -d` | HTTPS über Caddy, PostgreSQL als Metadaten-DB | ☐ |
| UC-13 | Datenquelle anlegen | UI, Verbindungstest | Testergebnis mit DB-Produkt und Version | ☐ |
| SEC-1 | Klartext-HTTP | ohne TLS und ohne Flag starten | Start wird verweigert | ☐ |
| SEC-2 | Rate-Limit | 20 falsche Logins | 429 für eine Minute | ☐ |
| SEC-3 | Enumeration | FIXED-Query ohne Freigabe aufrufen | 404, nicht 403 | ☐ |
| OPS-1 | Backup | Metadaten-DB und `data/master.key` sichern, neu einspielen | hinterlegte Credentials weiterhin nutzbar | ☐ |
| OPS-2 | Monitoring | `/actuator/health`, `/actuator/prometheus` | UP, Metriken vorhanden | ☐ |
