# Administrationsleitfaden

Alle Schritte laufen in der Admin-UI unter `/admin`; jede Funktion gibt es auch in der REST-API (`/api/v1`,
Basic Auth mit Admin-Konto). Rollen: `ADMIN` ändert, `VIEWER` liest. Die Oberfläche ist englisch; Beschriftungen
stehen hier im Original, z. B. „Datasources → New datasource“.

## 0. Bedienung

- **Overview:** Kennzahlen der letzten 24 Stunden (Ausführungen, Fehlerquote, Cache-Trefferquote, Dauer P50/P95)
  mit Verlauf pro Stunde und Vergleich zum Vortag; darunter „Needs attention“ (nicht erreichbare
  Datenquellen, aktive Queries ohne Schema-Snapshot, Fehler der letzten 24 h, Queries ohne Aufruf seit 30 Tagen)
  mit direkter Aktion, die zuletzt geänderten Queries, die Service-URL mit Excel-Anleitung und der Status der
  Datenquellen.
- **Erreichbarkeit der Datenquellen:** Gibt es für eine Datenquelle einen technischen Account, prüft der Dienst
  jede Minute eine Verbindung dieses Accounts (`jdbcodata.health.interval-ms`, `0` schaltet die Prüfung ab);
  „Check again“ prüft sofort. Ohne technischen Account gibt es keine Zugangsdaten zum Prüfen, dann zeigt die
  UI das Ergebnis der letzten Ausführung, die die Datenbank erreicht hat (Cache-Treffer zählen nicht).
- **Navigation:** Die Sidebar zeigt die Anzahl der Datenquellen, Queries, Service-User und technischen Accounts,
  einen Warnpunkt bei Problemen und unten die Service-URL zum Kopieren. Die Kopfzeile zeigt den Pfad, die
  globale Suche (**Ctrl+K** bzw. **⌘K**; Queries, Datenquellen, Service-User, Accounts und Seiten, Auswahl mit
  Pfeiltasten und Enter) und das Benutzermenü mit „Sign out“. `jdbcodata.ui.environment` (z. B. `DEV`, `TEST`,
  `PROD`) blendet ein Umgebungs-Badge ein; `PROD` erscheint rot, `TEST`/`STAGING` gelb.
- **Dark Mode:** Die Oberfläche folgt dem hellen oder dunklen Modus des Betriebssystems. Das Mond-/Sonnen-Symbol in
  der Kopfzeile schaltet um; im Benutzermenü lässt sich „System“, „Light“ oder „Dark“ fest wählen. Die Wahl gilt
  pro Browser (localStorage).
- **Listen:** Die Query-Liste hat eine Suche (Name, Beschreibung, Datenquelle, SQL), Filter für Datenquelle,
  Modus, Status, Snapshot und Nutzung, die Aufrufe der letzten 14 Tage als Mini-Balken, den letzten Aufruf,
  Zeilenaktionen (Feed-URL kopieren, Ausführungen ansehen) und blättert ab 25 Einträgen. Datenquellen,
  Service-User und technische Accounts lassen sich direkt in der Liste filtern.

## 1. Datenquelle anlegen

Datasources → New datasource: Name, JDBC-URL, optional Treiberklasse und Zeitzone, Limits (Timeout,
Max. Zeilen, parallele Ausführungen, Verbindungen pro Login). Mit „Test connection“ und eigenen DB-Zugangsdaten prüfen; die
Zugangsdaten werden nicht gespeichert.

Beispiele: `jdbc:oracle:thin:@//host:1521/SERVICE`, `jdbc:postgresql://host:5432/db`,
`jdbc:mariadb://host:3306/db` (auch für MySQL).

### Session-Einstellungen und Treiber-Properties

**Session settings** (eine Zeile `KEY=VALUE` je Einstellung) setzt der Service direkt nach dem
Verbindungsaufbau. Unter dem Feld steht, welche Schlüssel für die Datenbank der JDBC-URL erlaubt sind:

| Datenbank | Erzeugtes Statement | Erlaubte Schlüssel |
|---|---|---|
| Oracle | `ALTER SESSION SET …` | `NLS_LANGUAGE`, `NLS_TERRITORY`, `NLS_SORT`, `NLS_DATE_FORMAT`, `NLS_DATE_LANGUAGE`, `NLS_TIMESTAMP_FORMAT`, `NLS_TIMESTAMP_TZ_FORMAT`, `NLS_NUMERIC_CHARACTERS`, `NLS_CURRENCY`, `NLS_ISO_CURRENCY`, `NLS_DUAL_CURRENCY`, `NLS_CALENDAR`, `TIME_ZONE` |
| PostgreSQL | `SELECT set_config(…)` | `DateStyle`, `IntervalStyle`, `TimeZone`, `lc_monetary`, `lc_numeric`, `lc_time`, `search_path`, `extra_float_digits` |
| MySQL/MariaDB | `SET SESSION …` | `time_zone`, `lc_time_names`, `sql_mode`, `collation_connection`, `div_precision_increment` |
| H2 | `SET TIME ZONE …` | `TIME_ZONE` |

Beispiel Oracle:

```
NLS_LANGUAGE=GERMAN
NLS_TERRITORY=AUSTRIA
NLS_SORT=GERMAN
NLS_DATE_FORMAT=DD.MM.YYYY
```

`NLS_LANG` wirkt beim Thin-Treiber nicht. Das ist eine Umgebungsvariable des OCI-Clients; Sprache,
Territorium und Formate setzt man hier einzeln. `NLS_COMP` ist bewusst nicht erlaubt, weil
`LINGUISTIC` die binären Vergleiche des Push-Downs ändern würde.

Werte dürfen Buchstaben, Ziffern, Leerzeichen und `. , : / + - "` enthalten, aber keine Hochkommas,
Semikolons oder Backslashes. Bei PostgreSQL muss `DateStyle` mit `ISO` beginnen (z. B. `ISO, DMY`),
sonst trennt der JDBC-Treiber die Verbindung; Datumsausgaben formatiert man dort mit `to_char`. Eine Query kann einzelne Schlüssel im Feld „Session setting overrides“
überschreiben; alle anderen erbt sie von der Datenquelle. Eine Query mit Overrides bekommt eigene
Verbindungen. Ein Zurücksetzen der Session nach der Ausführung ist so nicht nötig. Der Cache-Schlüssel
enthält die wirksamen Einstellungen, ein geändertes Datumsformat liefert also keine alten Treffer.

Die Einstellungen wirken auf das SQL der Query: `ORDER BY`, `TO_CHAR` ohne Formatmaske, implizite
Datums- und Zahlkonvertierungen. OData-`$orderby`/`$filter` sortieren und vergleichen weiterhin binär,
im Speicher wie im Push-Down. „Test connection“ führt die Einstellungen der Datenquelle mit aus und
meldet ungültige Werte (z. B. `NLS_SORT=GERMANX`).

**JDBC driver properties** werden dem Treiber wie URL-Optionen übergeben (z. B.
`oracle.jdbc.defaultNChar=true`, `ApplicationName=jdbcodata`). Es gelten dieselben Verbote wie für
URL-Optionen; Credentials (`user`, `password`, `…Password`, `…secret`) sind nicht erlaubt.

## 2. Query anlegen

Queries → New query. Der Editor ist in Tabs gegliedert: **Definition** (Name, Datenquelle, SQL, Testlauf;
rechts Execution, Cache & Limits und Feed URL), **Parameters**, **Relations**, **Columns**, **Grants** und
**Versions**. Alle Änderungen – auch Spalteneinstellungen und Freigaben – speichert die Leiste am unteren Rand
gemeinsam (**Save** oder **Ctrl+S**/**⌘S**). Sie nennt die geänderten Bereiche, sagt, ob eine neue Version
entsteht, und markiert Tabs mit ungespeicherten Änderungen; beim Verlassen der Seite fragt der Browser nach.


1. **Name** ist der EntitySet-Name im Feed (`/odata/<Name>`), nur Buchstaben, Ziffern, `_`.
2. **SQL**: nur `SELECT` (auch `WITH`, `UNION`). Parameter als `:name` schreiben und in der Parametertabelle
   mit Typ, Pflicht-Flag und Default deklarieren. Sensitive Parameter werden im Protokoll maskiert.
3. **Test run** mit eigenen DB-Zugangsdaten: zeigt bis zu 100 Zeilen, Spaltentypen und Laufzeit.
4. **Save**, dann **Take schema snapshot**: erst danach erscheint die Query im Feed. Nach jeder
   SQL-Änderung muss der Snapshot erneuert werden.
5. **Columns**: Spalten ausblenden oder als Schlüssel markieren (Key „Columns“); sonst liefert der
   Feed einen synthetischen `RowId`.
   SQL-Arrays (PostgreSQL `text[]`, `int[]`, H2 `ARRAY`) und JSON-Spalten (`json`/`jsonb`, MySQL `JSON` oder
   Text) lassen sich unter **Publish as** strukturiert anbieten: Arrays von Werten als
   `Collection(Edm.String)`, `Collection(Edm.Int32)` usw., Arrays von JSON-Objekten als Collection eines
   ComplexTypes `<Query>_<Spalte>`, einzelne JSON-Objekte als ComplexType (`$filter=Kundendaten/Land eq 'AT'`,
   `$select=Kundendaten/Land`, `/odata/Bestellungen(1)/Kundendaten/Land`). Elementtyp und Felder ermittelt der
   Snapshot aus bis zu 100 Beispielzeilen (Feldtypen: Text, ganze Zahl, Dezimalzahl, Wahrheitswert, Datum,
   Zeitstempel, Listen von Werten; verschachtelte Objekte bleiben JSON-Text). Unter **Structure** sind Reihenfolge,
   Namen und Typen der Felder änderbar (eine Zeile je Feld: `Name Typ [JSON-Schlüssel]`), etwa weil PostgreSQL
   `jsonb` die Schlüssel umsortiert. Ein neuer Snapshot derselben SQL behält alle Spalteneinstellungen und hängt
   neue Felder hinten an. Danach gehen `$filter=Tags/any(t:t eq 'Neu')`,
   `Positionen/any(p:p/Betrag gt 1000)`, `Positionen/$count gt 2`, `/odata/Bestellungen(1)/Positionen` und
   `$select` mit Optionen: `$select=Tags($orderby=$this;$top=3),Positionen($filter=Betrag gt 100;$orderby=Betrag desc;$select=Produkt,Betrag;$count=true)`.
   Standard bleibt „text“ wie bisher (`{a,b}` bzw. der JSON-Text). Excel zeigt strukturierte Werte nur als Liste
   oder Record; „additionally as text“ liefert daneben die Property `<Spalte>Text` mit dem Text wie bisher.
   `/odata/Bestellungen(1)/Kategorien/$count` zählt Elemente (auch mit `$filter`). Collection-Spalten sind keine Schlüssel und werden nie in
   der Datenbank gefiltert.
6. **Relations** (optional): Verknüpfung zu einer anderen Query, z. B. `Umsatz.Kunde → Kunden` über
   `KundeId = KundeId`. Die Gegenrichtung (`Kunden.Umsaetze`, Häkchen „Many“) wird bei der Ziel-Query
   eingetragen; mit **Partner** verweisen beide aufeinander. Beziehungen erscheinen im `$metadata` als
   NavigationProperties und machen die Zusammenhänge in OData-Werkzeugen, Excel und Power BI sichtbar.
   Clients können sie mit `$expand` mitladen, z. B. `/odata/Umsatz?$expand=Kunde` oder
   `/odata/Kunden?$expand=Umsaetze($top=5;$orderby=Betrag desc)`. Auch in `$filter`/`$orderby` lassen sich
   Beziehungen nutzen: `/odata/Umsatz?$filter=Kunde/Region eq 'Nord'`,
   `/odata/Kunden?$filter=Umsaetze/any(u:u/Betrag gt 10000)` oder `$filter=Umsaetze/$count gt 50`.
   Navigationspfade gehen direkt in der URL: `/odata/Umsatz(1)/Kunde`, `/odata/Kunden(1)/Umsaetze?$top=5`.

   **Über Datenbanken hinweg:** Ziel-Query und eigene Query dürfen auf verschiedenen Datenquellen liegen,
   z. B. `Umsatz` (ERP-Datenbank) mit `Umsatz.Produkt → Produkte` (eigene Produkt-Datenbank) über
   `ProduktID = ProduktID`. Jede Query läuft auf ihrer Datenquelle, die Zeilen werden im Server zugeordnet.
   Zu beachten:
   - *Anmeldung:* Bei Durchreichen der Benutzeranmeldung muss diese auf beiden Datenbanken gültig sein;
     beim Service-User braucht jede Datenquelle eigene hinterlegte Zugangsdaten.
   - *Datentypen:* Zahlen werden typtolerant verglichen (`1` = `1.00`), Zahl und Text aber nicht
     (`'0042'` ≠ `42`) – dann in einer der beiden SQL-Abfragen casten.
   - *Datenmenge:* Die Ziel-Query wird pro Request einmal vollständig ausgeführt (höchstens 200.000
     expandierte Datensätze); große Stammdaten-Queries daher möglichst eng fassen.

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

- **Service-User** (Service users → New service user): Präfix `svc_`, Passwort, Gruppen; danach pro Datenquelle
  DB-Zugangsdaten hinterlegen. „force pass-through“ ignoriert hinterlegte Zugangsdaten.
- **Technische Accounts** (Technical accounts → New account): DB-Konto je Datenquelle, „Test connection“.
- **Freigaben** im Query-Editor (Tab „Grants“, nur FIXED): einzelne Service-User, Gruppen oder „all authenticated callers“.
  Nicht freigegebene Aufrufer sehen die Query nicht.
- PowerBI Service über Gateway: nur Service-User mit hinterlegten Zugangsdaten verwenden (PRD FR-32a).

### Push-Down

Im Query-Editor „Push-down“ einschalten. Dann laufen `$filter`, `$search`,
`$orderby`, `$top`, `$skip`, `$count` und `$apply`-Gruppierungen als gekapseltes SQL in der Datenbank, soweit
sie sich mit gleicher Bedeutung übersetzen lassen; der Rest wird wie bisher im Speicher ausgewertet. Sinnvoll für große Ergebnisse, die
Clients selten vollständig laden. Voraussetzung: Key „Columns“ mit Schlüsselspalte, Datenbank
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

Im Query-Editor „Result cache“ und TTL setzen. Der Cache gilt pro DB-User und Parameterwerten, Nutzer sehen
nie fremde Ergebnisse. Cache → Einträge ansehen, einzeln oder gesamt invalidieren; Clients können mit
`?refresh=true` erzwingen. Eine SQL-Änderung macht alte Einträge automatisch ungültig.

## 5. Statistik und Audit

Statistik: Zeitraum wählen, Kennzahlen, Top-Queries, langsamste Queries, Nutzung pro Aufrufer, Verlauf,
Rohdaten mit Filter und CSV-Export. Audit: alle Konfigurationsänderungen mit Admin, Zeit und Objekt, dazu Admin-Logins und -Logouts, abgelehnte Logins an Admin-Zugang und Feed mit IP sowie Testläufe mit SQL, ohne Parameterwerte.

## 6. Admin-Konten

Admin accounts → Create account (ADMIN oder VIEWER), Passwort ändern, deaktivieren. Mindestens ein aktiver ADMIN bleibt
immer bestehen; das eigene Konto kann nicht gelöscht werden.

Mit OIDC (siehe [Betriebshandbuch](OPERATIONS.md#single-sign-on-für-die-admin-ui)) erscheint auf der Login-Seite ein
Button pro Identity Provider. Diese Nutzer stehen nicht in der Liste der Admin-Konten: Die Rolle kommt bei jeder
Anmeldung vom Provider. Im Audit-Log tragen sie den Präfix `oidc:`, zum Beispiel `oidc:anna`.

## 7. API-Keys

API keys → New key. Ein Key ist für Maschinen-Clients gedacht und gilt entweder für Feeds oder für die REST-API, nie für
beides:

- **Feeds:** Der Key arbeitet als Service-User, mit dessen hinterlegten Credentials und Freigaben. Er ist selbst kein
  Datenbank-Login. Ein Service-User mit „force passthrough“ kann deshalb keinen Key haben, und einen Rückfall auf
  Pass-Through gibt es nicht. Ein deaktivierter Service-User sperrt auch seine Keys.
- **REST-API:** Der Key arbeitet mit der Rolle ADMIN oder VIEWER. Er kann keine Keys anlegen oder widerrufen, sonst könnte
  ein abgeflossener Key sich selbst verlängern.

Der Key wird nur einmal angezeigt, gespeichert ist nur sein Hash. Er läuft nach 1 bis 3650 Tagen ab, „Revoke“ wirkt sofort.
Zum Wechseln einen zweiten Key anlegen, den Client umstellen und den alten widerrufen. Gesendet wird er als
`Authorization: Bearer jdo_…`. Clients, die nur Basic Auth können, nutzen den Benutzer `apikey` und den Key als Passwort.
Ausführungsprotokoll und Audit-Log führen den Aufrufer als `apikey:<name>`.

## 8. Seed-Datei

Datenquellen und Queries lassen sich versioniert als YAML pflegen (`contrib/seed-example.yml`) und mit
`jdbcodata.seed.file` beim Start einspielen (Upsert nach Name). Mit `snapshotCredentials` wird der
Schema-Snapshot beim Start erzeugt; die Zugangsdaten werden nicht gespeichert. `keyColumns` markiert
Snapshot-Spalten als Schlüssel, `relations` deklariert Beziehungen (`name`, `target`, `columns`
als `eigene: ziel`, `toMany`, `partner`); fehlt `relations` in der Datei, bleiben vorhandene Beziehungen
erhalten. `pushDown: true` schaltet den Push-Down ein. `collectionColumns: [Kategorien, Positionen]` veröffentlicht
SQL-Arrays und JSON-Spalten strukturiert (Werte, ComplexType-Collection oder ComplexType, je nachdem, was der Snapshot
erkannt hat), `textCopyColumns` zusätzlich als Text. `sessionSettings` (an Datenquelle und Query) und
`driverProperties` (an der Datenquelle) sind Maps, z. B. `sessionSettings: {NLS_SORT: GERMAN}`; fehlen sie,
bleiben die gespeicherten Werte erhalten.
