#!/bin/sh
# Zwei Kundenbetreuer für die Pass-Through-Demo: dieselbe Query (MeineKunden) liefert je nach Basic-Auth-Login ein
# anderes Ergebnis, weil sich JdbcOdata mit genau diesen Zugangsdaten an der DB anmeldet und Row-Level-Security filtert.
# Passwörter kommen aus der Container-Umgebung; psql-Variablen vermeiden SQL-Injection.
set -eu
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
     -v anna_pw="$DEMO_ANNA_PASSWORD" -v bernd_pw="$DEMO_BERND_PASSWORD" <<'EOSQL'
CREATE ROLE betreuer_anna LOGIN PASSWORD :'anna_pw';
CREATE ROLE betreuer_bernd LOGIN PASSWORD :'bernd_pw';
COMMENT ON ROLE betreuer_anna IS 'Demo-Kundenbetreuerin, sieht per Row-Level-Security nur ihre Kunden';
COMMENT ON ROLE betreuer_bernd IS 'Demo-Kundenbetreuer, sieht per Row-Level-Security nur seine Kunden';

CREATE TABLE verkauf.kundenbetreuung (
    kunde_id  integer PRIMARY KEY REFERENCES verkauf.kunden (id),
    betreuer  varchar(63) NOT NULL          -- DB-Rolle des Betreuers, verglichen mit current_user
);
-- Feste Zuordnung: Kunden 1-12 betreut Anna, 13-20 Bernd, der Rest hat keinen Betreuer.
INSERT INTO verkauf.kundenbetreuung
SELECT g, CASE WHEN g <= 12 THEN 'betreuer_anna' ELSE 'betreuer_bernd' END
FROM generate_series(1, 20) AS g;

GRANT USAGE ON SCHEMA verkauf TO betreuer_anna, betreuer_bernd;
GRANT SELECT ON ALL TABLES IN SCHEMA verkauf TO betreuer_anna, betreuer_bernd;
GRANT SELECT ON verkauf.kundenbetreuung TO excel_demo, excel_nord;

ALTER TABLE verkauf.kundenbetreuung ENABLE ROW LEVEL SECURITY;
CREATE POLICY kundenbetreuung_eigene ON verkauf.kundenbetreuung FOR SELECT TO betreuer_anna, betreuer_bernd
    USING (betreuer = current_user);
CREATE POLICY kundenbetreuung_alle   ON verkauf.kundenbetreuung FOR SELECT TO excel_demo USING (true);
CREATE POLICY kundenbetreuung_nord   ON verkauf.kundenbetreuung FOR SELECT TO excel_nord
    USING (EXISTS (SELECT 1 FROM verkauf.kunden k WHERE k.id = kunde_id AND k.region = 'Nord'));

-- Auch Kunden, Bestellungen und Umsätze (View v_umsatz mit security_invoker) zeigen den Betreuern nur ihre Kunden.
CREATE POLICY kunden_betreuer ON verkauf.kunden FOR SELECT TO betreuer_anna, betreuer_bernd
    USING (EXISTS (SELECT 1 FROM verkauf.kundenbetreuung kb WHERE kb.kunde_id = id AND kb.betreuer = current_user));
CREATE POLICY bestellungen_betreuer ON verkauf.bestellungen FOR SELECT TO betreuer_anna, betreuer_bernd
    USING (EXISTS (SELECT 1 FROM verkauf.kunden k WHERE k.id = kunde_id));
EOSQL
# Produkt-Stammdaten in der zweiten Datenbank (siehe 05-produktstamm.sh), sonst scheitert dort die Query Produktstamm.
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d produktstamm <<'EOSQL'
GRANT USAGE ON SCHEMA pim TO betreuer_anna, betreuer_bernd;
GRANT SELECT ON ALL TABLES IN SCHEMA pim TO betreuer_anna, betreuer_bernd;
EOSQL
