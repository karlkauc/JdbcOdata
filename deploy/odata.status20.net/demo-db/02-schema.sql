-- Beispiel-Schema "verkauf": Kunden, Produkte, Bestellungen, Positionen und eine Umsatz-View.
CREATE SCHEMA verkauf;

CREATE TABLE verkauf.kunden (
    id          integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        varchar(120) NOT NULL,
    region      varchar(10)  NOT NULL CHECK (region IN ('Nord', 'Süd', 'Ost', 'West')),
    land        char(2)      NOT NULL,
    branche     varchar(40)  NOT NULL,
    kunde_seit  date         NOT NULL
);

CREATE TABLE verkauf.produkte (
    id           integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bezeichnung  varchar(120)  NOT NULL,
    kategorie    varchar(40)   NOT NULL,
    listenpreis  numeric(10,2) NOT NULL CHECK (listenpreis > 0)
);

CREATE TABLE verkauf.bestellungen (
    id             integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kunde_id       integer       NOT NULL REFERENCES verkauf.kunden (id),
    bestelldatum   date          NOT NULL,
    status         varchar(12)   NOT NULL CHECK (status IN ('OFFEN', 'VERSANDT', 'GELIEFERT', 'STORNIERT')),
    versandkosten  numeric(8,2)  NOT NULL DEFAULT 0
);
CREATE INDEX ON verkauf.bestellungen (kunde_id);
CREATE INDEX ON verkauf.bestellungen (bestelldatum);

CREATE TABLE verkauf.bestellpositionen (
    id             integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bestellung_id  integer       NOT NULL REFERENCES verkauf.bestellungen (id),
    produkt_id     integer       NOT NULL REFERENCES verkauf.produkte (id),
    menge          integer       NOT NULL CHECK (menge > 0),
    einzelpreis    numeric(10,2) NOT NULL
);
CREATE INDEX ON verkauf.bestellpositionen (bestellung_id);

-- security_invoker: die View läuft mit den Rechten des Aufrufers, damit Row-Level-Security greift.
CREATE VIEW verkauf.v_umsatz WITH (security_invoker = true) AS
SELECT p.id                                   AS "PositionId",
       b.id                                   AS "BestellungId",
       b.bestelldatum                         AS "Bestelldatum",
       EXTRACT(YEAR  FROM b.bestelldatum)::int AS "Jahr",
       EXTRACT(MONTH FROM b.bestelldatum)::int AS "Monat",
       b.status                               AS "Status",
       k.id                                   AS "KundeId",
       k.name                                 AS "Kunde",
       k.region                               AS "Region",
       k.land                                 AS "Land",
       pr.bezeichnung                         AS "Produkt",
       pr.kategorie                           AS "Kategorie",
       p.menge                                AS "Menge",
       p.einzelpreis                          AS "Einzelpreis",
       (p.menge * p.einzelpreis)::numeric(12,2) AS "Betrag"
FROM verkauf.bestellpositionen p
JOIN verkauf.bestellungen b ON b.id = p.bestellung_id
JOIN verkauf.kunden       k ON k.id = b.kunde_id
JOIN verkauf.produkte     pr ON pr.id = p.produkt_id;
