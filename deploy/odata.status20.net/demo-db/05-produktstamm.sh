#!/bin/sh
# Zweite Datenbank "produktstamm" im selben Container: eigene Produkt-Stammdaten (Hersteller, Artikelnummer, EAN, ...),
# die JdbcOdata über eine eigene Datenquelle liest und per Beziehung mit Umsatz/Produkte verknüpft
# (Beziehungen über Datenbanken hinweg). Die Rollen excel_demo/excel_nord gelten clusterweit, also auch hier.
set -eu
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "CREATE DATABASE produktstamm"
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d produktstamm <<'EOSQL'
CREATE SCHEMA pim;

CREATE TABLE pim.artikel (
    produkt_id      integer PRIMARY KEY,          -- gleiche ID wie verkauf.produkte.id in der Datenbank demo
    artikelnummer   varchar(20)  NOT NULL UNIQUE,
    ean             char(13)     NOT NULL,
    hersteller      varchar(60)  NOT NULL,
    herkunftsland   char(2)      NOT NULL,
    gewicht_kg      numeric(7,2) NOT NULL,
    garantie_monate integer      NOT NULL,
    lieferant       varchar(60)  NOT NULL,
    lagerbestand    integer      NOT NULL
);

-- Deterministische Stammdaten für die Produkte 1-40 (siehe 03-data.sql).
INSERT INTO pim.artikel
SELECT g,
       'ART-' || lpad(g::text, 5, '0'),
       '900' || lpad((g * 7919 % 1000000000)::text, 9, '0') || (g % 10)::text,
       (ARRAY['Nordwerk','Alpina','Kestrel','Fjordtec','Orbis','Veltra','Lumen','Tessera','Quanta','Rivo'])[1 + (g * 3) % 10],
       (ARRAY['CN','TW','US','DE','CH','VN'])[1 + g % 6],
       round(((ARRAY[2.1, 5.4, 0.9, 0.1, 0.8, 0.3, 9.5, 18.0, 2.6, 0.7])[1 + (g - 1) % 10] * (1 + (g - 1) / 10 * 0.15))::numeric, 2),
       (ARRAY[12, 24, 36, 60])[1 + (g - 1) / 10],
       (ARRAY['Handel Ost','Großhandel Mitte','Teilewerk Süd','Lager West'])[1 + g % 4],
       (g * 37) % 250
FROM generate_series(1, 40) AS g;

GRANT USAGE ON SCHEMA pim TO excel_demo, excel_nord;
GRANT SELECT ON ALL TABLES IN SCHEMA pim TO excel_demo, excel_nord;
EOSQL
