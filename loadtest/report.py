"""One line per scenario from a k6 summary and the sampled heap."""
import json
import sys

summary, heap_file, feed, heap, clients = sys.argv[1:6]
metrics = json.load(open(summary))["metrics"]


def count(name):
    return int(metrics.get(name, {}).get("values", {}).get("count", 0))


ok = metrics.get("ok_duration", {}).get("values", {})
heap_values = [int(line) for line in open(heap_file) if line.strip().isdigit()]
print(f"{feed}: heap limit {heap}, {clients} clients: 200={count('answers_200')} 503={count('answers_503')} "
      f"other={count('answers_other')}, seconds for a 200: median {ok.get('med', 0) / 1000:.1f} "
      f"p95 {ok.get('p(95)', 0) / 1000:.1f} max {ok.get('max', 0) / 1000:.1f}, "
      f"peak heap {max(heap_values, default=0)} MB")
