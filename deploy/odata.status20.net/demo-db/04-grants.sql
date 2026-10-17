-- Leserechte für die Excel-User und Row-Level-Security: excel_nord sieht nur Kunden der Region Nord
-- (und damit auch nur deren Bestellungen und Umsätze). Der Eigentümer demo_admin ist nicht betroffen.
GRANT USAGE ON SCHEMA verkauf TO excel_demo, excel_nord;
GRANT SELECT ON ALL TABLES IN SCHEMA verkauf TO excel_demo, excel_nord;

ALTER TABLE verkauf.kunden       ENABLE ROW LEVEL SECURITY;
ALTER TABLE verkauf.bestellungen ENABLE ROW LEVEL SECURITY;

CREATE POLICY kunden_alle      ON verkauf.kunden       FOR SELECT TO excel_demo USING (true);
CREATE POLICY kunden_nord      ON verkauf.kunden       FOR SELECT TO excel_nord USING (region = 'Nord');
CREATE POLICY bestellungen_alle ON verkauf.bestellungen FOR SELECT TO excel_demo USING (true);
CREATE POLICY bestellungen_nord ON verkauf.bestellungen FOR SELECT TO excel_nord
    USING (EXISTS (SELECT 1 FROM verkauf.kunden k WHERE k.id = kunde_id AND k.region = 'Nord'));
