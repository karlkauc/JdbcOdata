#!/bin/sh
# demo_admin ist Superuser und Eigentümer der Tabellen (umgeht Row-Level-Security). Über das Netz darf er sich
# nie anmelden: Der OData-Feed reicht beliebige Basic-Auth-Logins als Pass-Through an diese DB weiter, ein
# erratenes demo_admin-Passwort hätte also vollen Zugriff bedeutet. Lokal (Unix-Socket, docker exec) geht es weiter.
set -eu
sed -i '1i host all demo_admin all reject' "$PGDATA/pg_hba.conf"
