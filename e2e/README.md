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
- Pass-Through per Basic Auth: dieselbe URL `/odata/MeineKunden` liefert für `betreuer_anna` 12 und für
  `betreuer_bernd` 8 Kunden, `AngemeldetAls` ist jeweils der eigene DB-User, ein falsches Passwort gibt 401.

`tests/security.spec.ts` prüft die Härtung der Admin-UI, ebenfalls nur lesend: CSP-, Frame- und HSTS-Header,
Session-Cookie (`Secure`, `HttpOnly`, `SameSite=Lax`), alle Seiten der Navigation plus Query-Editor ohne
CSP-Verstöße oder Skriptfehler in der Konsole, Lösch-Rückfrage über `data-confirm` (wird abgebrochen, es geht kein
POST raus), automatisches Absenden des Statistik-Zeitraums und Dark Mode über `theme.js` nach einem Reload.

```bash
cd e2e && npm install && npx playwright install chromium
set -a && . ../deploy/odata.status20.net/.env && set +a
E2E_BASE_URL=https://odata.status20.net \
JDBCODATA_ADMIN_USER=$JDBCODATA_ADMIN_USER JDBCODATA_ADMIN_PASSWORD=$JDBCODATA_ADMIN_PASSWORD \
JDBCODATA_PASSWORD=$DEMO_READER_PASSWORD JDBCODATA_NORD_PASSWORD=$DEMO_NORD_PASSWORD \
JDBCODATA_ANNA_PASSWORD=$DEMO_ANNA_PASSWORD JDBCODATA_BERND_PASSWORD=$DEMO_BERND_PASSWORD npx playwright test
```

Die Tests schicken die Zugangsdaten mit jeder Anfrage. Bei aktivem anonymen Zugang kommt ohne Login kein 401, und
Browser würden sonst gar keine Zugangsdaten senden.

### Session-Einstellungen (schreibend, lokal)

`tests/session-settings.spec.ts` legt eine eigene Datenquelle und Query `e2e_session_…` an und löscht sie am Ende
über die REST-API wieder. Deshalb läuft die Spec nur mit `E2E_WRITE=1`, gedacht gegen die lokale Demo-Instanz
(H2, einziger erlaubter Schlüssel `TIME_ZONE`). Die JDBC-URL übernimmt sie von der Datenquelle `demo`.
- Datenquelle: fremder Schlüssel (`NLS_SORT`) mit Hinweis „Allowed: TIME_ZONE“, Hochkomma/Semikolon im Wert,
  verbotene Treiber-Properties (`socketFactory`, `password`) werden abgelehnt.
- Gespeicherte Werte stehen nach dem Neuladen im Formular.
- „Test connection“ meldet eine ungültige Zeitzone als „Session settings rejected“ und ist mit `+05:00` erfolgreich.
- Query: Der Testlauf zeigt den Offset der Datenquelle (`+05:00`), ein Override im ungespeicherten Formular gilt
  sofort (`-03:00`), ein fremder Schlüssel wird beim Speichern abgelehnt, ohne Override gilt wieder die Datenquelle.

```bash
# Instanz wie oben starten (Admin-Passwort "playwright-pw"), dann
cd e2e && E2E_WRITE=1 E2E_BASE_URL=http://localhost:18080 JDBCODATA_ADMIN_PASSWORD=playwright-pw \
  npx playwright test session-settings
```

`E2E_SOURCE_DATASOURCE`, `E2E_DB_USER` und `E2E_DB_PASSWORD` ändern die Vorgaben `demo`/`demo`/`demo`.

### Über den OData Viewer

Den Feed über den [OData Online Viewer](https://www.odata-viewer.online) prüft das Repo `odata-online-viewer`
(`e2e/tests/live-jdbcodata.spec.ts`). Der Test „basic auth: the same queries return each user's own rows“ meldet
sich im Viewer als `betreuer_anna` und danach als `betreuer_bernd` an (HTTP Basic im URL-Formular) und vergleicht:
- `MeineKunden`: dieselben Kunden wie ein direkter Aufruf an JdbcOdata als dieser User, `AngemeldetAls` und
  `Betreuer` nur der eigene User, keine Überschneidung zwischen Anna und Bernd.
- `Bestellungen`: `$count` wie beim direkten Aufruf, bei Anna und Bernd verschieden und kleiner als bei `excel_demo`.

Ein zweiter Test prüft, dass **Run** die im Daten-Explorer eingetippten Zugangsdaten mitschickt. Weil die Demo
anonymen Zugang hat, fiele eine fehlende Anmeldung sonst nicht als 401 auf, sondern nur durch andere Daten.

```bash
cd ~/webdav/odata-online-viewer/e2e
set -a && . ~/webdav/JdbcOdata/deploy/odata.status20.net/.env && set +a
E2E_EXTERNAL=1 E2E_BASE_URL=https://www.odata-viewer.online \
JDBCODATA_PASSWORD=$DEMO_READER_PASSWORD JDBCODATA_NORD_PASSWORD=$DEMO_NORD_PASSWORD \
JDBCODATA_ANNA_PASSWORD=$DEMO_ANNA_PASSWORD JDBCODATA_BERND_PASSWORD=$DEMO_BERND_PASSWORD \
npx playwright test live-jdbcodata
```

Für die Testinstanz des Viewers `E2E_BASE_URL=https://odata-viewer.status20.net` setzen.
