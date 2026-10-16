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
