# E2E-Tests (Playwright)

Prüfen eine laufende JdbcOdata-Instanz im Browser: Anmeldung und Seiten der Admin-Oberfläche, die
Spalteneinstellungen für Collections, den OData-Feed mit Basic Auth (Service-Dokument, `$metadata`, Abfragen mit
Collections und `$select`-Optionen) sowie Row-Level-Security. Die Tests ändern nichts an der Konfiguration.

```bash
cd e2e && npm install && npx playwright install chromium
set -a && . ../deploy/odata.status20.net/.env && set +a
E2E_BASE_URL=https://odata.status20.net \
JDBCODATA_ADMIN_USER=$JDBCODATA_ADMIN_USER JDBCODATA_ADMIN_PASSWORD=$JDBCODATA_ADMIN_PASSWORD \
JDBCODATA_PASSWORD=$DEMO_READER_PASSWORD JDBCODATA_NORD_PASSWORD=$DEMO_NORD_PASSWORD npx playwright test
```

Den Feed über den OData-Viewer prüft `odata-online-viewer/e2e/tests/live-jdbcodata.spec.ts`.
