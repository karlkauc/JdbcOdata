# Lasttest

50 Clients rufen je dreimal denselben Feed mit 100 000 Zeilen ab (Teststrategie in `docs/PRD.md`). Jedes Szenario läuft
in einem frischen Prozess, damit ein Speicherfehler im ersten das zweite nicht beeinflusst. Gebraucht werden Docker,
Python 3, curl und ein gebautes JAR.

```bash
./mvnw -q package -DskipTests
loadtest/run.sh 1g 50     # Heap-Limit, Clients
```

Während der Messung sollte nichts anderes auf dem Rechner laufen. Ein `503` ist eine korrekte Antwort des Limits; als
Fehler zählen nur andere Status. Rohdaten landen in `loadtest/results/`.

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
