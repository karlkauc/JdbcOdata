# Demo-Deployment odata.status20.net

JdbcOdata läuft hier als Docker-Stack hinter dem Host-Apache, der TLS terminiert (Let's Encrypt). Excel und
PowerBI greifen über `https://odata.status20.net/odata/` auf eine Demo-PostgreSQL mit Beispieldaten zu.

```
Excel ──HTTPS, Basic Auth──▶ Host-Apache (odata.status20.net) ──▶ 127.0.0.1:18085 ──▶ Container jdbcodata
                                                                        ├──JDBC──▶ demo-db  (PostgreSQL 16, Schema verkauf)
                                                                        └──JDBC──▶ meta-db  (PostgreSQL 16, Metadaten, Cache, Statistik)
```

## Dateien

| Datei | Zweck |
|---|---|
| `docker-compose.yml` | Stack `jdbcodata-status20`: Service, Metadaten-DB, Demo-DB |
| `.env.example` | Vorlage für `.env` (Secrets, nicht im Git) |
| `demo-db/` | Init-Skripte der Demo-DB: Rollen, Schema, Beispieldaten, Rechte und Row-Level-Security |
| `config/seed.yml` | Datenquelle `demo_postgres` und vier Queries, werden beim Start angelegt und per Snapshot im Feed sichtbar |
| `apache/` | vHost-Konfiguration für den Host-Apache (HTTP-Variante für den certbot-Erstlauf und die endgültige Fassung) |

## Start

```bash
cd deploy/odata.status20.net
cp .env.example .env            # alle Werte setzen, z. B. openssl rand -base64 24; Master-Key: openssl rand -base64 32
docker compose up -d --build    # erster Start baut das Image und befüllt die Demo-DB
docker compose logs -f jdbcodata   # "Seed /app/config/seed.yml applied: 1 datasource(s), 4 query(ies)" abwarten
```

Apache-vHost und Zertifikat: siehe Kommentar in `apache/odata.status20.net.conf`.

Stoppen mit `docker compose down`; die Volumes (`app-data`, `meta-data`, `demo-data`) bleiben erhalten. Ein
`docker compose down -v` löscht sie, beim nächsten Start werden Demo-DB und Seed neu aufgebaut.

## Beispieldaten

Schema `verkauf` in der Demo-DB: `kunden` (60, Regionen Nord/Süd/Ost/West), `produkte` (40), `bestellungen`
(3 000, 2024 bis Oktober 2026), `bestellpositionen` (ca. 7 500) und die View `v_umsatz`. Die Daten sind
deterministisch generiert (`setseed`), also bei jedem Neuaufbau gleich.

Zwei Lese-User zeigen, dass jede Query unter den Rechten des Aufrufers läuft:

| DB-User | Sieht |
|---|---|
| `excel_demo` | alle Kunden, Bestellungen und Umsätze |
| `excel_nord` | per Row-Level-Security nur Kunden der Region Nord und deren Bestellungen |

Die Passwörter stehen in `.env` (`DEMO_READER_PASSWORD`, `DEMO_NORD_PASSWORD`).

Queries im Feed (alle im Modus CALLER, Cache 5 Minuten pro User):

| EntitySet | Inhalt | Parameter |
|---|---|---|
| `Kunden` | Kundenstammdaten | – |
| `Produkte` | Produktliste | – |
| `Umsatz` | Umsatz auf Positionsebene | `p_jahr` (Standard 2025) |
| `UmsatzProMonat` | Summe je Monat, Region, Kategorie | `p_jahr` (Standard 2025) |

Beziehungen im `$metadata` (NavigationProperties): `Umsatz.Kunde → Kunden` und `Umsatz.Produkt → Produkte`
(je über `KundeId` bzw. `ProduktId`), Gegenrichtung `Kunden.Umsaetze` und `Produkte.Umsaetze`. Schlüssel sind
`KundeId`, `ProduktId` und `PositionId`. Beispiel: `/odata/Umsatz?$top=3&$expand=Kunde,Produkt` oder
`/odata/Kunden?$expand=Umsaetze($top=2;$orderby=Betrag desc)`; Filter über Beziehungen:
`/odata/Umsatz?$filter=Kunde/Region eq 'Nord'` oder `/odata/Kunden?$filter=Umsaetze/any(u:u/Betrag gt 30000)`.

## Anonymer Lesezugriff (Service-User `demo`)

Der Feed ist zusätzlich **ohne Login** lesbar, damit OData-Werkzeuge wie der
[OData Online Viewer](https://www.odata-viewer.online) ihn als Live-Beispiel öffnen können. Technisch läuft
jede Anfrage ohne `Authorization`-Header als Service-User `demo`
(`JDBCODATA_ODATA_ANONYMOUS_USER: demo` in `docker-compose.yml`, siehe `docs/CONFIG.md`). Anfragen mit
Login verhalten sich unverändert; im Ausführungsprotokoll steht der Aufrufer `anonymous`.

Der Service-User wird **einmalig** angelegt (Admin-UI → Service-User, oder per API). Er bekommt die
hinterlegten DB-Credentials von `excel_demo`, sieht also genau das, was `excel_demo` sieht:

```bash
set -a; . .env; set +a
API=https://odata.status20.net/api/v1; AUTH="$JDBCODATA_ADMIN_USER:$JDBCODATA_ADMIN_PASSWORD"
USER_ID=$(curl -s -u "$AUTH" -H 'Content-Type: application/json' -X POST "$API/users" \
  -d '{"username":"demo","displayName":"Anonymer Demo-Zugang (OData Viewer)","password":"'"$(openssl rand -base64 24)"'"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
DS_ID=$(curl -s -u "$AUTH" "$API/datasources" | python3 -c 'import json,sys; print([d["id"] for d in json.load(sys.stdin) if d["name"]=="demo_postgres"][0])')
curl -s -u "$AUTH" -H 'Content-Type: application/json' -X PUT "$API/users/$USER_ID/credentials/$DS_ID" \
  -d '{"dbUsername":"excel_demo","dbPassword":"'"$DEMO_READER_PASSWORD"'"}'
curl -s "https://odata.status20.net/odata/Kunden?\$top=1"     # ohne Login → 200
```

Das Service-Passwort wird nie gebraucht (anonyme Anfragen prüfen keines); ein Zufallswert genügt.
Abschalten: Zeile `JDBCODATA_ODATA_ANONYMOUS_USER` entfernen und `docker compose up -d`.

## In Excel einbinden

1. **Daten → Daten abrufen → Aus anderen Quellen → Aus OData-Feed**.
2. URL `https://odata.status20.net/odata/` eingeben. Für ein anderes Jahr: `https://odata.status20.net/odata/Umsatz?p_jahr=2024`.
3. Im Anmeldedialog **Standard** wählen, Benutzer `excel_demo` (oder `excel_nord`), Passwort aus `.env`, Ebene auf
   `https://odata.status20.net/odata/` lassen.
4. Im Navigator `Kunden`, `Produkte`, `Umsatz` oder `UmsatzProMonat` auswählen und laden.

Hat Excel einmal Zugangsdaten gespeichert, lassen sie sich unter **Daten → Datenquelleneinstellungen** ändern,
etwa um von `excel_demo` auf `excel_nord` zu wechseln.

PowerBI Desktop: **Daten abrufen → OData-Feed**, gleiche URL, Anmeldung „Standard“.

## Prüfen ohne Excel

```bash
source .env
curl -s -u "excel_demo:$DEMO_READER_PASSWORD" 'https://odata.status20.net/odata/'            # Service-Dokument
curl -s -u "excel_demo:$DEMO_READER_PASSWORD" 'https://odata.status20.net/odata/Kunden?$count=true&$top=0'
curl -s -u "excel_nord:$DEMO_NORD_PASSWORD"   'https://odata.status20.net/odata/Kunden?$count=true&$top=0'   # kleinerer Count
curl -s -D - -o /dev/null -u "excel_demo:$DEMO_READER_PASSWORD" 'https://odata.status20.net/odata/Umsatz?p_jahr=2025&$top=5' | grep -i x-cache
psql -h 127.0.0.1 -p 5440 -U demo_admin demo    # direkt in die Demo-DB
```

Admin-UI: `https://odata.status20.net/admin`, Benutzer `admin`, Passwort `JDBCODATA_ADMIN_PASSWORD` aus `.env`.
