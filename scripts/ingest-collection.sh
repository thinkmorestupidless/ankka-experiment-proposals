#!/usr/bin/env bash
# Ingests a collection of real articles through the Europe PMC adapter, for SC-003, against a running
# service in the live tier. It reaches the network and, with --extract, the model: never run it in CI.
#
#   EDP_API_TOKEN=... scripts/ingest-collection.sh [--extract] [--base http://localhost:9000]
#
# Every article is open access in Europe PMC except the last, a PMID with no full text there, which
# the service keeps abstract-only. The questions are those derived from "Can you make foie gras?".
set -euo pipefail

BASE="http://localhost:9000"
EXTRACT="false"
while [ $# -gt 0 ]; do
  case "$1" in
    --extract) EXTRACT="true" ;;
    --base) BASE="$2"; shift ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
  shift
done
TOKEN="${EDP_API_TOKEN:?EDP_API_TOKEN is required}"

# identifier | question | why it is in the collection (titles as Europe PMC lists them, 2026-10-17)
COLLECTION='
PMC13594933|Q1|Comparative compositional analysis of commercially produced mule duck and Landes goose foie gras
PMC12650120|Q1|Lipidomic and transcriptomic variations in lipid deposition during goose fatty liver formation
PMC13128355|Q1|Dynamics and reversibility of hepatic steatosis in male mule duck
PMC5932828|Q2|A chicken liver cell line and what it supports
PMC12673359|Q2|Integrative adipogenic engineering of cultured fat for cell-based meat
PMC9219492|Q3|Betaine promotes fat accumulation and reduces injury in Landes goose hepatocytes
PMC13570353|Q3|FABP4 regulates inflammation and lipid transport in goose primary hepatocytes
PMC12729497|Q4|Oleic acid increases lipid accumulation in duck hepatocytes
PMC13634536|Q4|PCYT2 inhibits lipid accumulation in chicken primary hepatocytes
PMC13430181|Q4|Exogenous adiponectin attenuates lipid accumulation in primary chicken hepatocytes
PMC10762281|Q5|Free fatty acid-induced lipid accumulation in HepG2 cells, measured by Oil Red O
PMC12985243|Q5|Sodium oleate-induced steatosis in HepG2 cells
PMC8362201|Q6|Scale-up economics for cultured meat
PMC13357269|Q6|A full process model of cultured meat manufacturing and its scale-up bottlenecks
pmid:39491705|Q1|Mitochondrial HKDC1 in goose fatty liver: no full text in Europe PMC, kept abstract-only
'

api() { curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "$@"; }

echo "$COLLECTION" | grep -v '^\s*$' | while IFS='|' read -r identifier question why; do
  started=$(api -X POST "$BASE/api/v1/sources:ingest" \
    -d "{\"adapter\":\"europepmc\",\"identifier\":\"$identifier\",\"extract\":$EXTRACT}")
  workflow=$(printf '%s' "$started" | sed -n 's/.*"workflowId":"\([^"]*\)".*/\1/p')
  if [ -z "$workflow" ]; then echo "$identifier: not started: $started" >&2; continue; fi
  for _ in $(seq 1 120); do
    state=$(api "$BASE/api/v1/workflows/ingestion/$workflow")
    case "$state" in *'"isTerminal":true'*) break ;; esac
    sleep 2
  done
  source_id=$(printf '%s' "$state" | sed -n 's/.*"sourceId":"\([^"]*\)".*/\1/p')
  status=$(printf '%s' "$state" | sed -n 's/.*\\"status\\":\\"\([^\\]*\)\\".*/\1/p')
  doc=$(api "$BASE/api/v1/sources/$source_id")
  retention=$(printf '%s' "$doc" | grep -o '"retention":{"type":"[a-z_]*"}' | head -1 | sed 's/.*"type":"\([a-z_]*\)".*/\1/')
  eligible=$(printf '%s' "$doc" | grep -o '"eligible":[a-z]*' | head -1 | cut -d: -f2)
  printf '%-14s %-3s %-28s %-12s retention=%-14s eligible=%-5s  %s\n' \
    "$identifier" "$question" "${source_id:-?}" "${status:-?}" "${retention:-none}" "${eligible:-?}" "$why"
done
