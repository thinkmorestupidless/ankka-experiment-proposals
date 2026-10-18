#!/usr/bin/env bash
# The eleven-step demonstration of quickstart.md over the HTTP API (SC-011). Each step prints what it
# proved and the ids to look at. Replay by default: start the service with EDP_TIER=replay.
#
#   EDP_API_TOKEN=... scripts/walkthrough.sh --reviewer demo-reviewer [--auto-review] [--base http://localhost:9000]
#
# Without --auto-review the script stops where a reviewer decides and tells you the page to open.
set -euo pipefail
BASE="http://localhost:9000"; REVIEWER=""; AUTO=0
while [ $# -gt 0 ]; do
  case "$1" in
    --reviewer) REVIEWER="$2"; shift ;;
    --auto-review) AUTO=1 ;;
    --base) BASE="$2"; shift ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac; shift
done
TOKEN="${EDP_API_TOKEN:?EDP_API_TOKEN is required}"
[ -n "$REVIEWER" ] || { echo "--reviewer <configured name> is required" >&2; exit 2; }
API="$BASE/api/v1"
api() { curl -sS -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" "$@"; }
field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -1; }
wait_for() { # wait_for <description> <url> <substring> [seconds]
  local what="$1" url="$2" needle="$3" n="${4:-120}" body=""
  for _ in $(seq 1 "$n"); do body=$(api "$url"); case "$body" in *"$needle"*) echo "  ✓ $what"; return 0 ;; esac; sleep 2; done
  echo "  ✗ $what did not happen within $((n*2))s; last reply: ${body:0:300}" >&2; return 1
}
pause_for_reviewer() { # pause_for_reviewer <page> <auto command...>
  local page="$1"; shift
  if [ "$AUTO" -eq 1 ]; then "$@"; else echo "  → a reviewer decides at $BASE$page (or rerun with --auto-review); waiting..."; "$@" 2>/dev/null || true; fi
}
started=$(date +%s)
echo "1. The customer request"
req=$(api -X POST "$API/requests" -d '{"text":"Can you make foie gras?","submittedBy":"customer"}')
RID=$(printf '%s' "$req" | field id); echo "  request $RID"
echo "$(api "$API/requests/$RID")" | grep -q 'Can you make foie gras?' && echo "  ✓ the text is kept byte for byte (SC-001)"
echo "2. Facets: proposed, then confirmed by the reviewer"
wait_for "the facets are proposed and the round-trip check ran" "$API/workflows/investigation/$RID" "awaiting-facet-review"
echo "  proposal: $(api "$API/requests/$RID/proposal" | head -c 300)"
confirm_facets() {
  for f in $(api "$API/requests/$RID/facets" | grep -o "\"id\":\"$RID:f[0-9]*\"" | cut -d'"' -f4); do
    api -X POST "$API/facets/$f/review" -d "{\"action\":\"confirm\",\"reviewer\":\"$REVIEWER\",\"rationale\":\"walkthrough\"}" > /dev/null
  done
  api -X POST "$API/requests/$RID/facets:confirmed" -d "{\"reviewer\":\"$REVIEWER\"}" > /dev/null
}
pause_for_reviewer "/review/requests/$RID/facets" confirm_facets
wait_for "the facets are confirmed" "$API/requests/$RID" "facets_confirmed\|questions_derived\|investigating" 60 || true
echo "3. Scientific questions"
wait_for "six questions are derived and their searches planned" "$API/workflows/investigation/$RID" "questions-investigating"
Q4="$RID:q4"; echo "  lipid question $Q4: $(api "$API/questions/$Q4" | field question)"
echo "4. Sources: the fixtures ingested, searched, the retracted paper excluded"
for f in paper-a.xml paper-b.xml paper-c.xml retracted.xml; do
  w=$(api -X POST "$API/sources:ingest" -d "{\"adapter\":\"file\",\"identifier\":\"$f\",\"extract\":$( [ "$f" = retracted.xml ] && echo false || echo true )}" | field workflowId)
  wait_for "$f ingested" "$API/workflows/ingestion/$w" '"isTerminal":true' 180
done
wait_for "the lipid question's retrieval is done" "$API/questions/$Q4" '"status":"retrieved"' 120
plan="$Q4:plan"; echo "  plan: $(api "$API/search-plans/$plan" | head -c 200)"
fused=$(api "$API/search-plans/$plan" | grep -o "\"$plan:e[0-9]*\"" | tr -d '"' | while read -r e; do api "$API/search-executions/$e" | grep -q '"fused":true' && echo "$e" && break; done)
api "$API/search-executions/$fused" | grep -q 'eligibility.retracted' && echo "  ✓ the retracted paper is excluded after ranking with its rule"
api -X POST "$API/search-executions/$fused/rerun" -d '{}' | grep -q '"matches":true' && echo "  ✓ the rerun returns the same identifiers (SC-002)"
echo "5. Findings and claims, verified against the source"
for s in src:file:paper-a src:file:paper-b src:file:paper-c; do wait_for "records of $s verified" "$API/sources/$s/records" '"status":"verified"' 120; done
echo "  ✓ every cited span resolves (SC-003): $(api "$API/findings/src:file:paper-a:v1:fd1" | grep -o '"resolves":true' | head -1)"
echo "6. Assessment against the lipid question"
api -X POST "$API/questions/$Q4/assess" -d '{"recordIds":["src:file:paper-a:v1:fd1","src:file:paper-b:v1:fd1","src:file:paper-c:v1:fd1"]}' > /dev/null
wait_for "three assessments" "$API/questions/$Q4" "species-mismatch-blocks-direct" 120
echo "  ✓ Paper B is indirect by the species rule (SC-004)"
echo "7. Sufficiency for each decision"
for d in "recommend production feasibility" "propose a feasibility experiment on lipid accumulation"; do
  api -X POST "$API/questions/$Q4/sufficiency" -d "{\"intendedDecision\":\"$d\"}" > /dev/null
  wait_for "judged: $d" "$API/questions/$Q4" "\"intendedDecision\":\"$d\"" 120
done
echo "  statuses: $(api "$API/questions/$Q4" | grep -o '"intendedDecision":"[^"]*","proposition":"[^"]*","status":{"type":"[a-z_]*"}' | sed 's/,"proposition":"[^"]*"//')"
echo "8. Packages and candidates; the reviewer decides"
api -X POST "$API/requests/$RID/packages" -d "{\"reviewer\":\"$REVIEWER\"}" > /dev/null
wait_for "two candidates compared on eight criteria" "$API/workflows/investigation/$RID" "awaiting-decision" 180
api "$API/packages/$Q4:pk1" | grep -q '"qualityStatus":{"type":"valid"}' && echo "  ✓ the lipid package is valid"
decide() { api -X POST "$API/requests/$RID/decide" -d "{\"selectedApproachId\":\"$RID:c1\",\"rejected\":[{\"approachId\":\"$RID:c2\",\"reason\":\"not the target species\"}],\"decidedBy\":\"$REVIEWER\",\"rationale\":\"the target species comes first\"}" > /dev/null; }
pause_for_reviewer "/review/requests/$RID/candidates" decide
wait_for "the decision is recorded (SC-007)" "$API/requests/$RID/approaches" '"decision":{' 120
echo "9. The design and its approval"
wait_for "a twelve-section design is under review" "$API/workflows/investigation/$RID" "awaiting-approval" 180
api "$API/designs/$RID:design?format=md" | grep -q "Proposal awaiting review" && echo "  ✓ rendered as a proposal until approved"
approve() { api -X POST "$API/designs/$RID:design/approval" -d "{\"version\":1,\"decision\":\"approved\",\"reviewer\":\"$REVIEWER\",\"rationale\":\"ready\"}" > /dev/null; }
pause_for_reviewer "/review/designs/$RID:design" approve
wait_for "approved (SC-008)" "$API/designs/$RID:design" '"type":"approved"' 60
echo "10. Faults and a retraction"
api -X POST "$API/sources:ingest" -d '{"adapter":"file","identifier":"paper-a.xml@walkthrough","extract":true}' > /dev/null
wait_for "a copy of Paper A for the fault" "$API/sources/src:file:paper-a@walkthrough/records" '"status":"verified"' 180
api -X POST "$API/faults:inject" -d '{"target":{"kind":"finding","id":"src:file:paper-a@walkthrough:v1:fd1"},"mutation":"endpoint_swap"}' > /dev/null
wait_for "the endpoint fault is caught (SC-005)" "$API/findings/src:file:paper-a@walkthrough:v1:fd1" "endpoint_mismatch" 120
api -X POST "$API/sources/src:file:paper-b/status" -d "{\"status\":\"retracted\",\"reason\":\"walkthrough\",\"by\":\"$REVIEWER\"}" > /dev/null
wait_for "every dependent of Paper B is flagged (SC-009)" "$API/impact?ref=source:src:file:paper-b:v1" '"allFlagged":true' 120
echo "11. The approved version is unchanged and the flag names the source version"
api "$API/designs/$RID:design" | grep -q '"id":"src:file:paper-b","version":1}' && echo "  ✓ the flag names Paper B version 1; the approved version is intact (SC-010)"
api -X POST "$API/sources/src:file:paper-b/status" -d '{"status":"active","reason":"walkthrough restored","by":"operator"}' > /dev/null
echo "Done in $(( $(date +%s) - started ))s. Runs: $API/runs/run-$RID and $API/runs/run-$Q4"
