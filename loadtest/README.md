# Lasttest

50 Clients rufen je dreimal denselben Feed mit 100 000 Zeilen ab, einmal ohne und einmal mit Cache (Teststrategie in
`docs/PRD.md`). Dazu exportieren 5 Clients gleichzeitig 1 000 000 Zeilen ohne Cache. Jedes Szenario läuft in einem
frischen Prozess, damit ein Speicherfehler im ersten das nächste nicht beeinflusst. Gebraucht werden Docker, Python 3,
curl und ein gebautes JAR. `LOADTEST_ROWS` ändert die Größe des Exports, `LOADTEST_SCENARIOS` die Szenarien (siehe
`run.sh`).

```bash
./mvnw -q package -DskipTests
loadtest/run.sh 1g 50     # Heap-Limit, Clients
```

Während der Messung sollte nichts anderes auf dem Rechner laufen. Ein `503` ist eine korrekte Antwort des Limits; als
Fehler zählen nur andere Status. Rohdaten landen in `loadtest/results/`.

## Ergebnis vom 2026-11-03: Streaming

Gleicher Rechner und gleiche Einstellungen wie am 2026-11-01, Stand `74ba280` mit Spool-Datei (#49), Entities erst beim
Schreiben (#50) und DB-Cursor (#51). Die Heap-Spitze ist der belegte Heap einschließlich Garbage, einmal pro Sekunde
abgefragt; sie zeigt den Trend, nicht den Mindestbedarf. Temp-Dateien sind die Spool-Dateien aller Antworten zusammen.

| Szenario | Heap | 200 | 503 | Fehler | Sekunden p95 | Heap-Spitze | 2026-11-01 mit Budget | Temp-Dateien |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| ohne Cache, 100 000 Zeilen, 50 Clients | 1 GB | 60 | 90 | 0 | 13,4 | 209 MB | 941 MB | 23 MB |
| ohne Cache, 100 000 Zeilen, 50 Clients | 2 GB | 55 | 95 | 0 | 9,9 | 310 MB | 1866 MB | 23 MB |
| mit Cache, 100 000 Zeilen, 50 Clients | 1 GB | 150 | 0 | 0 | 10,4 | 647 MB | 866 MB | 412 MB |
| mit Cache, 100 000 Zeilen, 50 Clients | 2 GB | 150 | 0 | 0 | 10,9 | 740 MB | 2012 MB | 460 MB |
| Export ohne Cache, 1 000 000 Zeilen, 5 Clients | 1 GB | 5 | 0 | 0 | 20,0 | 207 MB | nicht möglich | 248 MB |
| Export ohne Cache, 1 000 000 Zeilen, 5 Clients | 2 GB | 5 | 0 | 0 | 19,3 | 222 MB | nicht möglich | 237 MB |

Eine Antwort mit 100 000 Zeilen hat 11,7 MB, eine mit 1 000 000 Zeilen 119 MB. Kein `OutOfMemoryError`.

- **Ohne Cache** sinkt die Heap-Spitze bei 1 GB von 941 auf 209 MB. Die Zeilen liegen nicht mehr im Heap. Der Anteil
  der 503 bleibt, denn ihn bestimmt weiter `maxConcurrent` 10 mit zwei Wartesekunden.
- **Mit Cache** liegt die Tabelle selbst im Heap. Die Spitze sinkt weniger deutlich, von 866 auf 647 MB, dafür
  kommen 412 MB Temp-Dateien hinzu. Der Heap hängt hier vor allem an der Größe des Caches.
- **Der Export** mit 1 Mio. Zeilen war vorher nicht möglich: `max-rows` begrenzte auf 100 000 Zeilen, und bei rund
  1 KB pro Zeile im Heap hätte ein einzelner Export den Heap von 1 GB gesprengt. Jetzt laufen fünf gleichzeitig mit
  rund 210 MB Heap-Spitze.
- Die Antwortzeiten bleiben gleich, Streaming macht die Abrufe nicht schneller.
- **Für die Dimensionierung:** Den Plattenplatz für `/tmp` setzt `spool-max-mb`, im Helm-Chart `tmpSizeLimit`. Bei 50
  parallelen Cache-Treffern mit je 12 MB waren 460 MB belegt.

## Ergebnis vom 2026-11-01

8 Kerne, PostgreSQL 17 und Service auf demselben Rechner, `max_concurrent` 10, `poolSize` 2, fünf Spalten. 150 Abrufe.

| Szenario | Stand | 200 | 503 | Fehler | Sekunden p95 | Heap-Spitze | `OutOfMemoryError` |
|---|---|---:|---:|---:|---:|---:|---:|
| ohne Cache, 1 GB | vor dem Fix | 74 | 76 | 0 | 13,1 | 1005 MB | 0 |
| ohne Cache, 1 GB | mit Budget | 66 | 84 | 0 | 10,2 | 941 MB | 0 |
| ohne Cache, 2 GB | vor dem Fix | 72 | 78 | 0 | 9,8 | 1889 MB | 0 |
| ohne Cache, 2 GB | mit Budget | 77 | 73 | 0 | 10,7 | 1866 MB | 0 |
| mit Cache, 1 GB | vor dem Fix | 7 | 0 | 143 | 99,5 | nicht verwertbar | 632 |
| mit Cache, 1 GB | mit Budget | 150 | 0 | 0 | 10,4 | 866 MB | 0 |
| mit Cache, 2 GB | vor dem Fix | 39 | 0 | 111 | 297,0 | nicht verwertbar | 111 |
| mit Cache, 2 GB | mit Budget | 150 | 0 | 0 | 10,5 | 2012 MB | 0 |

Die Heap-Spitze vor dem Fix ist nicht verwertbar, weil die Prometheus-Abfrage unter Speichernot ausfiel. Ohne Cache
scheitern rund die Hälfte der Abrufe mit 503, denn das Limit ist mit `maxConcurrent` 10 und zwei Wartesekunden für
Hunderttausende Zeilen eng; mehr Durchsatz bringt ein höheres `maxConcurrent` oder der Cache. Unter dieser Last wartet
auch ein Cache-Treffer rund 10 Sekunden. Der Speicherbedarf je Antwort bleibt hoch, solange nicht gestreamt wird (O-15).
