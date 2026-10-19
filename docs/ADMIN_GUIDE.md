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
   SQL-Arrays (PostgreSQL `text[]`, `int[]`, H2 `ARRAY`) und JSON-Spalten (`json`/`jsonb`, MySQL `JSON` oder
   Text) lassen sich unter **Veröffentlichen als** strukturiert anbieten: Arrays von Werten als
   `Collection(Edm.String)`, `Collection(Edm.Int32)` usw., Arrays von JSON-Objekten als Collection eines
   ComplexTypes `<Query>_<Spalte>`, einzelne JSON-Objekte als ComplexType (`$filter=Kundendaten/Land eq 'AT'`,
   `$select=Kundendaten/Land`, `/odata/Bestellungen(1)/Kundendaten/Land`). Elementtyp und Felder ermittelt der
   Snapshot aus bis zu 100 Beispielzeilen (Feldtypen: Text, ganze Zahl, Dezimalzahl, Wahrheitswert, Datum,
   Zeitstempel, Listen von Werten; verschachtelte Objekte bleiben JSON-Text). Unter **Struktur** sind Reihenfolge,
   Namen und Typen der Felder änderbar (eine Zeile je Feld: `Name Typ [JSON-Schlüssel]`), etwa weil PostgreSQL
   `jsonb` die Schlüssel umsortiert. Ein neuer Snapshot derselben SQL behält alle Spalteneinstellungen und hängt
   neue Felder hinten an. Danach gehen `$filter=Tags/any(t:t eq 'Neu')`,
   `Positionen/any(p:p/Betrag gt 1000)`, `Positionen/$count gt 2`, `/odata/Bestellungen(1)/Positionen` und
   `$select` mit Optionen: `$select=Tags($orderby=$this;$top=3),Positionen($filter=Betrag gt 100;$orderby=Betrag desc;$select=Produkt,Betrag;$count=true)`.
   Standard bleibt „Text“ wie bisher (`{a,b}` bzw. der JSON-Text). Excel zeigt strukturierte Werte nur als Liste
   oder Record; „zusätzlich als Text“ liefert daneben die Property `<Spalte>Text` mit dem Text wie bisher.
   `/odata/Bestellungen(1)/Kategorien/$count` zählt Elemente (auch mit `$filter`). Collection-Spalten sind keine Schlüssel und werden nie in
   der Datenbank gefiltert.
6. **Beziehungen** (optional): Verknüpfung zu einer anderen Query, z. B. `Umsatz.Kunde → Kunden` über
   `KundeId = KundeId`. Die Gegenrichtung (`Kunden.Umsaetze`, Häkchen „Viele“) wird bei der Ziel-Query
   eingetragen; mit **Partner** verweisen beide aufeinander. Beziehungen erscheinen im `$metadata` als
   NavigationProperties und machen die Zusammenhänge in OData-Werkzeugen, Excel und Power BI sichtbar.
   Clients können sie mit `$expand` mitladen, z. B. `/odata/Umsatz?$expand=Kunde` oder
   `/odata/Kunden?$expand=Umsaetze($top=5;$orderby=Betrag desc)`. Auch in `$filter`/`$orderby` lassen sich
   Beziehungen nutzen: `/odata/Umsatz?$filter=Kunde/Region eq 'Nord'`,
   `/odata/Kunden?$filter=Umsaetze/any(u:u/Betrag gt 10000)` oder `$filter=Umsaetze/$count gt 50`.
   Navigationspfade gehen direkt in der URL: `/odata/Umsatz(1)/Kunde`, `/odata/Kunden(1)/Umsaetze?$top=5`.

Volltextsuche und Aggregation laufen auf jedem Feed: `/odata/Kunden?$search=Wien` oder
`/odata/Umsatz?$apply=groupby((Region),aggregate(Betrag with sum as Summe))&$orderby=Summe desc`.
Weitere Möglichkeiten: `$compute=Betrag mul 1.2 as Brutto`, `$apply=groupby((rollup(Region,Land)),aggregate($count as N))`,
`/odata/Kunden(1)/Umsaetze/$ref`, `/odata/$all?$search=Wien` und
`/odata/$crossjoin(Umsatz,Kunden)?$filter=Umsatz/KundeId eq Kunden/KundeId`.

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
- **Technische Accounts** (Technische Accounts → Neuer Account): DB-Konto je Datenquelle, Verbindung testen.
- **Freigaben** im Query-Editor (nur FIXED): einzelne Service-User, Gruppen oder „alle authentifizierten“.
  Nicht freigegebene Aufrufer sehen die Query nicht.
- PowerBI Service über Gateway: nur Service-User mit hinterlegten Zugangsdaten verwenden (PRD FR-32a).

### Push-Down

Im Query-Editor „Filter in der Datenbank auswerten (Push-Down)“ setzen. Dann laufen `$filter`, `$search`,
`$orderby`, `$top`, `$skip`, `$count` und `$apply`-Gruppierungen als gekapseltes SQL in der Datenbank, soweit
sie sich mit gleicher Bedeutung übersetzen lassen; der Rest wird wie bisher im Speicher ausgewertet. Sinnvoll für große Ergebnisse, die
Clients selten vollständig laden. Voraussetzung: Schlüsselmodus „Spalten“ mit Schlüsselspalte, Datenbank
PostgreSQL, H2, Oracle oder MySQL/MariaDB. Gepushte Abrufe umgehen den Cache. Der Antwort-Header
`X-Push-Down` zeigt, was in der Datenbank lief. In `seed.yml`: `pushDown: true`.

Queries mit Parametern sind zusätzlich als OData-Funktion aufrufbar: `GetUmsatz(p_jahr=2024)`.

### Änderungsverfolgung

Clients, die nur Änderungen nachladen wollen, senden `Prefer: odata.track-changes` und folgen später dem
`@odata.deltaLink` der Antwort: Er liefert neue, geänderte und gelöschte Zeilen seit dem letzten Abruf. Der
Service merkt sich dafür einen Schnappschuss in der Metadaten-Datenbank (7 Tage, auch über Neustarts hinweg;
danach antwortet der Link mit 410 und der Client lädt neu). Voraussetzung sind Schlüsselspalten; solche Abrufe
umgehen den Ergebnis-Cache.

## 4. Cache

Im Query-Editor „Cache aktiv“ und TTL setzen. Der Cache gilt pro DB-User und Parameterwerten, Nutzer sehen
nie fremde Ergebnisse. Cache → Einträge ansehen, einzeln oder gesamt invalidieren; Clients können mit
`?refresh=true` erzwingen. Eine SQL-Änderung macht alte Einträge automatisch ungültig.

## 5. Statistik und Audit

Statistik: Zeitraum wählen, Kennzahlen, Top-Queries, langsamste Queries, Nutzung pro Aufrufer, Verlauf,
Rohdaten mit Filter und CSV-Export. Audit: alle Konfigurationsänderungen mit Admin, Zeit und Objekt.

## 6. Admin-Konten

Admin-Konten → Konto anlegen (ADMIN oder VIEWER), Passwort ändern, deaktivieren. Mindestens ein aktiver ADMIN bleibt
immer bestehen; das eigene Konto kann nicht gelöscht werden.

## 7. Seed-Datei

Datenquellen und Queries lassen sich versioniert als YAML pflegen (`contrib/seed-example.yml`) und mit
`jdbcodata.seed.file` beim Start einspielen (Upsert nach Name). Mit `snapshotCredentials` wird der
Schema-Snapshot beim Start erzeugt; die Zugangsdaten werden nicht gespeichert. `keyColumns` markiert
Snapshot-Spalten als Schlüssel, `relations` deklariert Beziehungen (`name`, `target`, `columns`
als `eigene: ziel`, `toMany`, `partner`); fehlt `relations` in der Datei, bleiben vorhandene Beziehungen
erhalten. `pushDown: true` schaltet den Push-Down ein. `collectionColumns: [Kategorien, Positionen]` veröffentlicht
SQL-Arrays und JSON-Spalten strukturiert (Werte, ComplexType-Collection oder ComplexType, je nachdem, was der Snapshot
erkannt hat), `textCopyColumns` zusätzlich als Text.
