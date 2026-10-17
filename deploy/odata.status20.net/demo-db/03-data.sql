-- Deterministische Beispieldaten (setseed): 60 Kunden, 40 Produkte, 3000 Bestellungen 2024-2026.
SELECT setseed(0.42);

INSERT INTO verkauf.kunden (name, region, land, branche, kunde_seit)
SELECT (ARRAY['Alpen','Donau','Wiener','Tiroler','Steirische','Salzburger','Kärntner','Linzer',
              'Grazer','Bregenzer','Burgenland','Mühlviertler','Alpha','Beta','Gamma','Delta'])[1 + (g * 7) % 16]
       || ' ' ||
       (ARRAY['Handel','Technik','Logistik','Bau','Energie','Textil','Lebensmittel','Metall','Software','Druck'])[1 + (g * 3) % 10]
       || ' ' ||
       (ARRAY['GmbH','AG','KG','OG','e.U.'])[1 + g % 5],
       (ARRAY['Nord','Süd','Ost','West'])[1 + floor(random() * 4)::int],
       (ARRAY['AT','AT','AT','DE','CH'])[1 + floor(random() * 5)::int],
       (ARRAY['Industrie','Handel','Dienstleistung','Öffentliche Hand'])[1 + floor(random() * 4)::int],
       DATE '2015-01-01' + floor(random() * 3200)::int
FROM generate_series(1, 60) AS g;

INSERT INTO verkauf.produkte (bezeichnung, kategorie, listenpreis)
SELECT (ARRAY['Notebook','Monitor','Tastatur','Maus','Docking-Station','Headset','Drucker','Server','Switch','Router'])[1 + (g - 1) % 10]
       || ' ' ||
       (ARRAY['Basic','Office','Pro','Max'])[1 + (g - 1) / 10],
       (ARRAY['Computer','Peripherie','Peripherie','Peripherie','Zubehör','Zubehör','Drucker','Infrastruktur','Netzwerk','Netzwerk'])[1 + (g - 1) % 10],
       round(((ARRAY[900,250,40,25,180,60,320,2400,450,150])[1 + (g - 1) % 10] * (1 + ((g - 1) / 10) * 0.6) * (0.9 + random() * 0.2))::numeric, 2)
FROM generate_series(1, 40) AS g;

INSERT INTO verkauf.bestellungen (kunde_id, bestelldatum, status, versandkosten)
SELECT 1 + floor(random() * 60)::int,
       DATE '2024-01-01' + floor(random() * 1019)::int,          -- bis Mitte Oktober 2026
       CASE WHEN r < 0.75 THEN 'GELIEFERT' WHEN r < 0.90 THEN 'VERSANDT' WHEN r < 0.97 THEN 'OFFEN' ELSE 'STORNIERT' END,
       (ARRAY[0, 4.90, 9.90, 19.90])[1 + floor(random() * 4)::int]
FROM generate_series(1, 3000) AS g, LATERAL (SELECT random() AS r) AS x;

INSERT INTO verkauf.bestellpositionen (bestellung_id, produkt_id, menge, einzelpreis)
SELECT b.id,
       pr.id,
       1 + floor(random() * 10)::int,
       round((pr.listenpreis * (0.85 + random() * 0.15))::numeric, 2)
FROM verkauf.bestellungen b
CROSS JOIN LATERAL generate_series(1, 1 + floor(random() * 4 + b.id * 0)::int) AS pos
JOIN LATERAL (SELECT id, listenpreis FROM verkauf.produkte
              WHERE id = 1 + floor(random() * 40 + pos * 0)::int) AS pr ON true;

ANALYZE verkauf.kunden; ANALYZE verkauf.produkte; ANALYZE verkauf.bestellungen; ANALYZE verkauf.bestellpositionen;
