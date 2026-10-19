# Sicherheit

Bedrohungsmodell, Betriebsregeln und Ergebnis der Security Review vom 19.10.2026. Konfiguration im Detail:
[CONFIG.md](CONFIG.md), Betrieb: [OPERATIONS.md](OPERATIONS.md#sicherheit-im-betrieb).

## Bedrohungsmodell

| Akteur | Darf | Darf nicht |
|---|---|---|
| Anonymer Internet-Client | Bei gesetztem `anonymous-user`: Queries, die genau diesem Service-User (oder seiner Gruppe) freigegeben sind | Schema oder Daten anderer Queries sehen, Logins unbegrenzt raten, fremde User aussperren |
| Pass-Through-Aufrufer | Queries der Datenquellen, an denen sein DB-Login gilt; die DB entscheidet über Zeilen (RLS, Grants) | `$metadata` ohne gültigen Login, Ergebnisse oder Async-Jobs anderer Aufrufer |
| Service-User | Hinterlegte Credentials, FIXED-Queries mit Freigabe | Hinterlegte Passwörter auslesen |
| VIEWER (Admin-UI) | Lesen von Konfiguration, Statistik, Cache, Audit | Ändern; Passwörter oder sensible Parameterwerte sehen |
| ADMIN (Admin-UI) | Datenquellen, Queries, Konten verwalten | Code auf dem Server ausführen, Dateien des Service lesen (Master-Key, Metadaten-DB) |

Annahmen: TLS endet am Service oder an einem Proxy, der `X-Forwarded-*` vom Client verwirft. Die DB-Konten der
Datenquellen haben nur Leserechte. Superuser-Konten der Datenquellen sind über das Netz nicht anmeldbar, weil
der Feed jede unbekannte Basic-Auth-Anmeldung als DB-Login weiterreicht.

## Schutzmaßnahmen im Code

| Thema | Umsetzung |
|---|---|
| Client-IP | `getRemoteAddr()`; hinter dem Proxy setzt sie Tomcats RemoteIpValve (`server.forward-headers-strategy=native`) nur aus `X-Forwarded-For` vertrauenswürdiger Proxies. `framework` erzeugt eine Warnung beim Start |
| Rate-Limit | `RateLimitFilter` prüft jede Anfrage mit Basic Auth (auch auf UI-Pfaden), `/odata`, `/api` und den Login-POST auf dem dekodierten Pfad. Sperre pro IP ab dem Limit, pro Benutzername erst ab dem Fünffachen; Zähler läuft eine Minute nach dem letzten Fehlversuch ab |
| JDBC-URLs | `JdbcUrlPolicy`: nur bekannte Netzwerk-Treiber (erweiterbar), keine Optionen, die Klassen/Dateien laden, SQL beim Verbinden ausführen oder Read-only abschalten, keine Passwörter in der URL, Treiberklasse muss `java.sql.Driver` sein. Eingebettetes H2 nur mit `allow-embedded-h2` (Demo, Tests) |
| Admin-SQL | `SqlValidator`: nur `SELECT`, keine schreibenden CTEs, kein `SELECT INTO` (auch in UNION-Zweigen), kein `FOR UPDATE/SHARE`, keine `/*! */`-Kommentare |
| OData-Identität | `$metadata` und Service-Dokument erst nach gültigem DB-Login (Pass-Through) bzw. Service-Passwort; einheitliche 401-Meldung ohne Datasource- oder User-Namen. „Alle Authentifizierten“ schließt den anonymen Zugang aus |
| Admin-UI | Session-Cookie `HttpOnly`, `SameSite=Lax`, `Secure` bei HTTPS; CSRF; Content-Security-Policy ohne Inline-Skripte; `frame-ancestors 'none'`; Sessions enden, wenn das Konto deaktiviert, gelöscht oder Passwort/Rolle geändert wird |
| Secrets | AES-256-GCM für hinterlegte Passwörter, an ihren Datensatz gebunden (AAD); Master-Key- und Initialpasswort-Datei werden mit Rechten 600 angelegt; das generierte Admin-Passwort steht nie im Log; BCrypt Cost 12 (erfolgreiche Service-User-Prüfungen 5 Minuten im Speicher, nur als SHA-256) |
| Protokolle | Sensible Parameter als `***` in Ausführungsprotokoll, Rohoptionen, CSV und Cache-Übersicht; CSV-Zellen mit `= + - @` werden gegen Formel-Ausführung entschärft |
| Ressourcen | OData-Bodies maximal 10 MB, Zeilenlimit, Timeouts, Parallelitätslimit pro Datenquelle, Regex-Budget |
| Abhängigkeiten | `./mvnw -Psecurity verify` (OWASP Dependency-Check, Abbruch ab CVSS 7); Base-Images per Digest gepinnt (Update: `docker buildx imagetools inspect <tag>`) |

## Review 19.10.2026

Umfang: Quellcode, Deployment/Konfiguration, Abhängigkeiten, nicht-destruktive Live-Checks gegen
odata.status20.net. Nicht gefunden: SQL-Injection über OData (alle Werte gebunden, Bezeichner nur aus dem
Snapshot), XSS, XXE/Deserialisierung, Secrets im Repository.

| ID | Befund | Schwere | Status |
|---|---|---|---|
| H1 | IP-Rate-Limit per gefälschtem `X-Forwarded-For` umgehbar (erster Eintrag, Strategie `framework`) | hoch | behoben (`native`, `getRemoteAddr()`) |
| H2 | Basic Auth auf UI-Pfaden und kodierte Pfade (`/%6fdata/`) am Rate-Limit vorbei | hoch | behoben |
| H3 | ADMIN konnte über JDBC-URL (H2 `INIT=RUNSCRIPT`, Metadaten-DB, beliebige Treiberklasse) Code ausführen und Dateien lesen | hoch | behoben (`JdbcUrlPolicy`) |
| H4 | `.env` der Demo für alle lokalen User lesbar, für www-data schreibbar | hoch | behoben (600, ACL entfernt) |
| H5 | Superuser `demo_admin` der Demo-DB per Pass-Through aus dem Internet ratbar | hoch | behoben (`demo-db/00-hba.sh`; bestehendes Volume: einmaliger Schritt im Deploy-README) |
| M1 | SELECT-Prüfung umgehbar (schreibende CTEs, `INTO` in UNION, `/*! */`, `FOR UPDATE`) | mittel | behoben |
| M2 | Anonymer Zugang sah und las FIXED-Queries mit Freigabe „alle Authentifizierten“ | mittel | behoben |
| M3 | `$metadata`/Service-Dokument mit beliebigen Basic-Credentials | mittel | behoben |
| M4 | Async-Monitor nur über den (ungeprüften) Benutzernamen gebunden | mittel | behoben über M3 (Login wird vorher geprüft) |
| M5 | Session-Cookie ohne `Secure`/`SameSite` | mittel | behoben |
| M6 | Client-`X-Forwarded-Host`/`-Prefix` in `@odata.context`/`nextLink` | mittel | behoben (Proxy verwirft, `native` ignoriert Prefix) |
| M7 | Username-Lockout als DoS gegen beliebige Konten | mittel | behoben (Username erst ab 5× Limit) |
| M8 | Sensible Parameter im Cache und in den Rohoptionen des Protokolls unmaskiert | mittel | behoben |
| M9 | Unbegrenzte Request-Bodies (`$batch`, Async) | mittel | behoben (10 MB) |
| M10 | Fehlermeldungen nannten Datasource-/Service-User-Namen | mittel | behoben für Login-Fehler; DB-Fehlertexte bei der Ausführung bleiben für angemeldete Aufrufer sichtbar (gewollt, Diagnose) |
| M11 | commons-io 2.13.0 (CVE-2024-47554), kein CVE-Scan | mittel | behoben (2.22.0, Profil `security`) |
| M12 | Generiertes Admin-Passwort im Log | mittel | behoben (Datei mit Rechten 600) |
| L1 | CSV-Formel-Injection | niedrig | behoben |
| L2 | Sessions überleben Deaktivierung/Rollenwechsel/Passwortänderung; `lastLoginAt` auch bei Fehlversuch | niedrig | behoben (`AdminSessions`, Session-Registry; Login-Zeit nur bei Erfolg) |
| L3 | Keine CSP, doppelte widersprüchliche Header (Apache + Spring) | niedrig | behoben |
| L4 | Service-User-Enumeration über Meldung und Antwortzeit | niedrig | behoben (einheitliche Meldung, abgelehnte Pass-Through-Logins kosten eine BCrypt-Prüfung) |
| L5 | Master-Key-Datei kurz mit Standardrechten; GCM ohne AAD | niedrig | behoben (Datei mit 600 angelegt; Chiffrate an Datensatz gebunden, Altbestand wird beim Start umgeschlüsselt) |
| L6 | BCrypt Cost 10 statt 12 (NFR-04) | niedrig | behoben |
| L7 | Passwörter in JDBC-URLs für VIEWER und im Audit-Log sichtbar | niedrig | neue URLs mit Passwort werden abgelehnt; Altbestand beim nächsten Bearbeiten |
| L8 | Cache-Schlüssel ohne Escaping (Kollision nur innerhalb desselben DB-Users) | niedrig | behoben (längenpräfixierter Hash-Input) |
| L9 | Javadoc „sliding window“ stimmte nicht | niedrig | behoben |
| L10 | Fehler-JSON escapte `\` und Steuerzeichen nicht | niedrig | behoben |
| L11 | Deployment-Hygiene (systemd-Härtung, Caddy-Header, Image-Digests, Beispiel-Passwörter) | niedrig | behoben (Images per Digest, keine Platzhalter-Passwörter, `change-me…` verhindert den Start, Demo-Compose nur auf 127.0.0.1) |
