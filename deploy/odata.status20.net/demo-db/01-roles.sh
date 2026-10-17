#!/bin/sh
# Legt die Lese-User an, mit denen sich Excel am Feed anmeldet (Pass-Through auf die DB).
# Passwörter kommen aus der Container-Umgebung; psql-Variablen vermeiden SQL-Injection.
set -eu
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
     -v reader_pw="$DEMO_READER_PASSWORD" -v nord_pw="$DEMO_NORD_PASSWORD" <<'EOSQL'
CREATE ROLE excel_demo LOGIN PASSWORD :'reader_pw';
CREATE ROLE excel_nord LOGIN PASSWORD :'nord_pw';
COMMENT ON ROLE excel_demo IS 'Demo-Leser, sieht alle Regionen';
COMMENT ON ROLE excel_nord IS 'Demo-Leser, sieht per Row-Level-Security nur Region Nord';
EOSQL
