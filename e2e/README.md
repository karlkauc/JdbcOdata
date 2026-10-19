# End-to-End-Check mit Playwright

`check.mjs` klickt die Admin-UI im Headless-Chromium durch (Login, Datenquellen-Test, Query-Editor mit
CodeMirror und Parameterzeilen, htmx-Testlauf und Snapshot, Service-User mit Credentials, technischer Account,
FIXED-Modus mit Freigaben, OData-Zugriff als Service-User, Cache-, Statistik-, Audit-, Admin- und
Einstellungsseiten, VIEWER-Rolle) und bricht bei JavaScript-Fehlern, HTTP-5xx oder fehlenden Texten ab.

```bash
# App mit leerem Datenverzeichnis starten (Admin-Passwort muss "playwright-pw" sein)
mkdir -p /tmp/jdbcodata-e2e && echo 18080 > /tmp/jdbcodata-e2e/port.txt
JDBCODATA_ADMIN_PASSWORD=playwright-pw java -jar target/jdbcodata.jar --spring.profiles.active=demo \
  --server.port=18080 --jdbcodata.data-dir=/tmp/jdbcodata-e2e/data \
  --spring.datasource.url="jdbc:h2:file:/tmp/jdbcodata-e2e/data/meta;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE" \
  --jdbcodata.demo.jdbc-url="jdbc:h2:file:/tmp/jdbcodata-e2e/data/demo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE" &

# Playwright (Node) einmalig installieren, dann den Check laufen lassen
cd e2e && npm install playwright && npx playwright install chromium
PW_DIR=/tmp/jdbcodata-e2e node check.mjs
```

Screenshots landen in `$PW_DIR/shots`. Der Check ist nicht Teil von `mvnw verify`, weil er einen Browser braucht.

## Screenshots

`screenshots/` enthält die Aufnahmen des letzten Laufs: Login, Übersicht, Datenquelle mit Verbindungstest,
Query-Editor nach Testlauf und Snapshot, Service-User mit hinterlegten Credentials, FIXED-Query mit Freigaben,
Statistik, Einstellungen, Query-Liste, Cache, Audit-Log, Admin-Konten sowie die Übersicht bei 420 px Breite.
`screenshots/before/` hält den Stand von Release 1.0.0 vor dem UI-Polishing; `screenshots/compare.html` zeigt
beide Stände nebeneinander (einfach im Browser öffnen). Ein neuer Lauf schreibt nach `$PW_DIR/shots`; zum
Aktualisieren von dort kopieren.

`14-live-status20-dashboard.png` und `15-live-status20-queries.png` stammen nicht aus `check.mjs`, sondern aus dem
Live-Deployment `https://odata.status20.net` (siehe `deploy/odata.status20.net/`): Übersicht mit der Datenquelle
`demo_postgres` und die Query-Liste mit den vier PostgreSQL-Queries, aufgenommen durch den Host-Apache.

## Live-Tests gegen eine laufende Instanz (`npx playwright test`)

`tests/jdbcodata.spec.ts` prüft eine laufende Instanz, standardmäßig `https://odata.status20.net`, nur lesend:
- Admin-Oberfläche: Login, Fehllogin, Navigation, Spalteneinstellungen der Query `Bestellungen` mit Collections,
  ComplexType, Textkopie und Struktur, Statistik.
- OData-Feed: Service-Dokument, `$metadata`, Collections mit `$select`-Optionen, `…/Positionen/$count`,
  `…/Kundendaten/Land/$value`, Push-Down-Header und `$apply`.
- Row-Level-Security (`excel_nord`) und anonymer Zugang.

```bash
cd e2e && npm install && npx playwright install chromium
set -a && . ../deploy/odata.status20.net/.env && set +a
E2E_BASE_URL=https://odata.status20.net \
JDBCODATA_ADMIN_USER=$JDBCODATA_ADMIN_USER JDBCODATA_ADMIN_PASSWORD=$JDBCODATA_ADMIN_PASSWORD \
JDBCODATA_PASSWORD=$DEMO_READER_PASSWORD JDBCODATA_NORD_PASSWORD=$DEMO_NORD_PASSWORD npx playwright test
```

Die Tests schicken die Zugangsdaten mit jeder Anfrage. Bei aktivem anonymen Zugang kommt ohne Login kein 401, und
Browser würden sonst gar keine Zugangsdaten senden. Den Feed über den OData-Viewer prüft
`odata-online-viewer/e2e/tests/live-jdbcodata.spec.ts`.
