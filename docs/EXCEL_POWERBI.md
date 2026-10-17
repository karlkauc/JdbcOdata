# Anleitung für Excel und PowerBI

## Excel (Microsoft 365)

1. **Daten → Daten abrufen → Aus anderen Quellen → Aus OData-Feed**.
2. URL eintragen: `https://<host>/odata/` (Service-Dokument) oder direkt eine Query, z.B.
   `https://<host>/odata/Umsatz?p_jahr=2025`.
3. Anmeldung **Standard**: Benutzername und Passwort eingeben.
   - Pass-Through: DB-User und DB-Passwort.
   - Service-User: `svc_…` und Service-Passwort.
   Excel speichert die Zugangsdaten pro URL (Datenquelleneinstellungen).
4. Im Navigator die Query wählen, **Laden** oder **Daten transformieren**.
5. Aktualisieren über **Daten → Alle aktualisieren**. Parameter stehen in der URL; zum Ändern die Abfrage im
   Power-Query-Editor öffnen und die Quelle anpassen.

Hinweise:

- `?refresh=true` an die URL anhängen, um den Cache zu umgehen.
- Filter in Power Query werden als `$filter` an den Service gesendet und dort ausgewertet; sehr große
  Ergebnisse (über dem Zeilenlimit) liefern einen Fehler 413, dann Parameter oder Filter einschränken.
- Bei selbstsigniertem Zertifikat (Caddy „internal“ oder Test-Keystore) muss das Zertifikat der CA am
  Client als vertrauenswürdig installiert werden, sonst meldet Excel einen Verbindungsfehler.
- Datum/Zeit kommt als `DateTimeOffset` in UTC; Excel wandelt in die lokale Zeitzone um.

## PowerBI Desktop

Deklarierte Beziehungen zwischen Queries (Admin-UI → Query → Beziehungen) stehen im `$metadata` als
Navigationseigenschaften. Der OData-Connector zeigt sie als zusätzliche Spalten (`Kunde`, `Umsaetze`)
und lädt sie beim Aufklappen per `$expand` mit. Für große Modelle ist es meist besser, die Tabellen einzeln
zu laden und die Beziehung in Power BI über die Schlüsselspalten (z. B. `KundeId`) anzulegen.


1. **Daten abrufen → OData-Feed**, URL wie oben, Anmeldung **Standard**.
2. Tabelle wählen, laden. Query Folding reicht `$filter`, `$select`, `$top` an den Service weiter.

## PowerBI Service (Cloud) mit Gateway

- Dataset veröffentlichen, im Gateway eine Datenquelle vom Typ OData mit **Standard**-Authentifizierung
  anlegen.
- Im Gateway ausschließlich einen **Service-User** mit hinterlegten DB-Zugangsdaten eintragen, keine
  persönlichen DB-Konten (PRD FR-32a). Für Reports mit Row-Level-Security bleibt PowerBI Desktop mit
  persönlichem Login der Weg.
- Geplante Aktualisierung: Intervall an die Cache-TTL der Query anpassen.

## Fehlerbilder

| Meldung | Bedeutung |
|---|---|
| 401, erneute Passwortabfrage | DB oder Service hat die Zugangsdaten abgelehnt |
| Query fehlt im Navigator | inaktiv, ohne Schema-Snapshot oder nicht freigegeben (FIXED) |
| 400 „Missing required parameter“ | Pflichtparameter in der URL ergänzen, z.B. `?p_jahr=2025` |
| 413 | Ergebnis zu groß |
| 429 | zu viele Fehlversuche, eine Minute warten |
| 503 „busy“ | Datenquelle ausgelastet, später erneut versuchen |
