# Konfigurationsreferenz

Alle Werte stehen in `config/application.yml` (neben dem JAR) oder als Umgebungsvariablen. Spring Boot
bildet `jdbcodata.cache.max-size-mb` auf `JDBCODATA_CACHE_MAX_SIZE_MB` ab, `server.port` auf `SERVER_PORT`.

| Schlüssel | Default | Bedeutung |
|---|---|---|
| `server.port` | `8080` | HTTP(S)-Port |
| `server.ssl.key-store` | – | PKCS12-Keystore für eingebautes TLS; mit `key-store-password`, `key-store-type: PKCS12` |
| `server.forward-headers-strategy` | – | `native` hinter einem Reverse-Proxy: Tomcat wertet `X-Forwarded-*` nur von internen Proxies aus (127.x, 10.x, 172.16–31.x, 192.168.x), damit Redirects und Feed-URLs `https` verwenden, das Session-Cookie `Secure` bekommt und das Rate-Limit die echte Client-IP sieht. **Nicht** `framework`: dann zählt der erste, vom Client fälschbare `X-Forwarded-For`-Eintrag (Warnung im Log). Andere Proxy-Netze: `server.tomcat.remoteip.internal-proxies` |
| `jdbcodata.admin.user` | `admin` | Name des ersten Admin-Kontos (nur beim ersten Start verwendet) |
| `jdbcodata.admin.password` | leer | Passwort des ersten Admin-Kontos; leer = generiert und in `<data-dir>/initial-admin-password` (Rechte 600) abgelegt, nie im Log. Datei nach dem ersten Login löschen |
| `jdbcodata.master-key` | leer | AES-256-Schlüssel (Base64, 32 Byte) für hinterlegte DB-Passwörter; leer = Key-Datei `data/master.key` |
| `jdbcodata.data-dir` | `./data` | Verzeichnis für Key-Datei und H2-Dateien |
| `jdbcodata.meta.url` | H2-Datei in `data/` | JDBC-URL der Metadaten-DB (H2 oder PostgreSQL) |
| `jdbcodata.meta.username` / `password` | `sa` / leer | Login der Metadaten-DB |
| `jdbcodata.odata.max-rows` | `100000` | Harte Obergrenze pro Ergebnis (über Datenquelle und Query nur unterschreitbar) |
| `jdbcodata.odata.default-timeout-sec` | `60` | Timeout, wenn die Datenquelle keinen setzt |
| `jdbcodata.odata.concurrency-wait-ms` | `2000` | Wartezeit auf einen freien Ausführungsplatz der Datenquelle, danach 503 |
| `jdbcodata.odata.page-size` | `0` | Serverseitiges Paging: Zeilen pro Antwort, danach `@odata.nextLink` mit `$skiptoken`. `0` = aus. Clients können mit `Prefer: odata.maxpagesize=n` kleinere Seiten anfordern (bei gesetztem Wert nie größer). Excel und Power BI folgen den Links automatisch. |
| `jdbcodata.odata.anonymous-user` | leer | Name eines Service-Users, unter dem Anfragen **ohne** `Authorization`-Header laufen (öffentlicher Demo-Feed, OData-Explorer ohne Login). Leer = jede Anfrage braucht Basic Auth. Der User muss aktiv sein, darf nicht auf Pass-Through erzwungen sein und braucht hinterlegte DB-Credentials oder FIXED-Queries; Anfragen mit Header werden unverändert authentifiziert; ein Header, der kein gültiges HTTP Basic ist (z. B. Bearer), ergibt 401. Im Ausführungsprotokoll erscheint der Aufrufer als `anonymous`. |
| `jdbcodata.cache.max-size-mb` | `1024` | Gesamtgröße des persistenten Caches (komprimiert) |
| `jdbcodata.cache.memory-mb` | `256` | In-Memory-Schicht (Caffeine) |
| `jdbcodata.cache.cleanup-interval-ms` | `300000` | Aufräumjob für abgelaufene Einträge und Größenlimit |
| `jdbcodata.stats.retention-days` | `90` | Aufbewahrung des Ausführungsprotokolls |
| `jdbcodata.stats.async` | `true` | Protokoll über eigenen Writer-Thread schreiben |
| `jdbcodata.usage.db-url` | leer | PostgreSQL-URL einer eigenen Statistik-DB; leer = es wird nichts erfasst oder gesendet |
| `jdbcodata.usage.db-user` / `db-password` | leer | Login dieser Datenbank |
| `jdbcodata.usage.hash-secret` | leer | Pflicht zusammen mit `db-url`, Schlüssel für das Tages-Salt des Besucher-Hashes |
| `jdbcodata.security.allow-plain-http` | `false` | Start ohne TLS erlauben (nur hinter TLS-Proxy oder lokal) |
| `jdbcodata.security.rate-limit-per-minute` | `20` | Fehlversuche pro Client-IP, danach 429; pro Benutzername gilt das Fünffache (verteilte Angriffe), damit ein einzelner Client keinen fremden User aussperren kann. Ein Zähler läuft eine Minute nach dem letzten Fehlversuch ab; `0` deaktiviert |
| `jdbcodata.security.allowed-jdbc-urls` | leer | Zusätzliche JDBC-URL-Präfixe für Treiber aus `lib/`, z. B. `jdbc:sqlserver:`. Immer erlaubt: `jdbc:postgresql:`, `jdbc:mariadb:`, `jdbc:oracle:thin:`. H2 über TCP/SSL nur mit eigenem Präfix (`jdbc:h2:tcp:`). Optionen, die Klassen oder Dateien laden, beim Verbinden SQL ausführen oder Read-only abschalten (`INIT`, `socketFactory`, `sslfactory`, `readOnlyMode`, `allowLocalInfile`, `allowMultiQueries`, `initSql` …), und Passwörter in der URL werden abgelehnt |
| `jdbcodata.security.allow-embedded-h2` | `false` | Eingebettete H2-Datenbanken (`jdbc:h2:mem:`, `jdbc:h2:file:`) als Datenquelle erlauben. Sie laufen in der JVM des Service und könnten dessen Dateien lesen (Master-Key, Metadaten-DB): nur Demo-Profil und Tests |
| `jdbcodata.seed.file` | leer | YAML mit Datenquellen und Queries, beim Start per Name eingespielt |
| `management.endpoints.web.exposure.include` | `health,prometheus` | Actuator-Endpunkte; `/actuator/prometheus` nur für Admin-Konten |
| `server.servlet.session.cookie.same-site` | `lax` | SameSite des Admin-Session-Cookies; `Secure` setzt Tomcat bei HTTPS selbst |
| `logging.file.name` | – | z.B. `logs/jdbcodata.log` für Datei-Logging |

Profile: `demo` legt eine lokale H2-Beispieldatenbank an, lädt `seed-demo.yml` und erlaubt Klartext-HTTP.

Zusätzliche JDBC-Treiber: JAR-Dateien in `lib/` legen; die Start-Skripte und das Docker-Image laden sie über
`-Dloader.path=lib`.

**Oracle:** Der Treiber wird nicht mitgeliefert. `ojdbc11.jar` nach `lib/` legen (ZIP: `-Dloader.path=lib`, Docker: Volume `/app/lib`) oder selbst mit `./mvnw -Poracle clean package` bauen. Die URL-Regel `jdbc:oracle:thin:` ist bereits erlaubt.
