# Administrationsleitfaden

Alle Schritte laufen in der Admin-UI unter `/admin`; jede Funktion gibt es auch in der REST-API (`/api/v1`,
Basic Auth mit Admin-Konto). Rollen: `ADMIN` ändert, `VIEWER` liest.

## 1. Datenquelle anlegen

Datenquellen → Neue Datenquelle: Name, JDBC-URL, optional Treiberklasse und Zeitzone, Limits (Timeout,
Max. Zeilen, parallele Ausführungen). Mit „Verbindung testen“ und eigenen DB-Zugangsdaten prüfen; die
Zugangsdaten werden nicht gespeichert.

Beispiele: `jdbc:oracle:thin:@//host:1521/SERVICE`, `jdbc:postgresql://host:5432/db`,
`jdbc:mariadb://host:3306/db`, `jdbc:mysql://host:3306/db`.

## 2. Query anlegen

Queries → Neue Query:

1. **Name** ist der EntitySet-Name im Feed (`/odata/<Name>`), nur Buchstaben, Ziffern, `_`.
2. **SQL**: nur `SELECT` (auch `WITH`, `UNION`). Parameter als `:name` schreiben und in der Parametertabelle
   mit Typ, Pflicht-Flag und Default deklarieren. Sensitive Parameter werden im Protokoll maskiert.
3. **Testlauf** mit eigenen DB-Zugangsdaten: zeigt bis zu 100 Zeilen, Spaltentypen und Laufzeit.
4. **Speichern**, dann **Schema-Snapshot erstellen**: erst danach erscheint die Query im Feed. Nach jeder
   SQL-Änderung muss der Snapshot erneuert werden.
5. **Spalten**: Spalten ausblenden oder als Schlüssel markieren (Schlüsselmodus „Spalten“); sonst liefert der
   Feed einen synthetischen `RowId`.

Jede Änderung an SQL, Parametern oder Datenquelle erzeugt eine Version; ältere Versionen lassen sich
wiederherstellen.

## 3. Identität wählen

| Modus | Wer meldet sich in Excel an | Womit läuft die Query |
|---|---|---|
| CALLER (Pass-Through) | DB-User und DB-Passwort | genau diese Zugangsdaten |
| CALLER mit Service-User | Service-User (`svc_…`) und Service-Passwort | die für die Datenquelle hinterlegten DB-Zugangsdaten |
| FIXED | beliebiger freigegebener Aufrufer | technischer Account der Query |

- **Service-User** (Service-User → Neu): Präfix `svc_`, Passwort, Gruppen; danach pro Datenquelle
  DB-Zugangsdaten hinterlegen. „Pass-Through erzwingen“ ignoriert hinterlegte Zugangsdaten.
- **Technische Accounts** (Techn. Accounts → Neu): DB-Konto je Datenquelle, Verbindung testen.
- **Freigaben** im Query-Editor (nur FIXED): einzelne Service-User, Gruppen oder „alle authentifizierten“.
  Nicht freigegebene Aufrufer sehen die Query nicht.
- PowerBI Service über Gateway: nur Service-User mit hinterlegten Zugangsdaten verwenden (PRD FR-32a).

## 4. Cache

Im Query-Editor „Cache aktiv“ und TTL setzen. Der Cache gilt pro DB-User und Parameterwerten, Nutzer sehen
nie fremde Ergebnisse. Cache → Einträge ansehen, einzeln oder gesamt invalidieren; Clients können mit
`?refresh=true` erzwingen. Eine SQL-Änderung macht alte Einträge automatisch ungültig.

## 5. Statistik und Audit

Statistik: Zeitraum wählen, Kennzahlen, Top-Queries, langsamste Queries, Nutzung pro Aufrufer, Verlauf,
Rohdaten mit Filter und CSV-Export. Audit: alle Konfigurationsänderungen mit Admin, Zeit und Objekt.

## 6. Admin-Konten

Admins → Konto anlegen (ADMIN oder VIEWER), Passwort ändern, deaktivieren. Mindestens ein aktiver ADMIN bleibt
immer bestehen; das eigene Konto kann nicht gelöscht werden.

## 7. Seed-Datei

Datenquellen und Queries lassen sich versioniert als YAML pflegen (`contrib/seed-example.yml`) und mit
`jdbcodata.seed.file` beim Start einspielen (Upsert nach Name). Mit `snapshotCredentials` wird der
Schema-Snapshot beim Start erzeugt; die Zugangsdaten werden nicht gespeichert.
