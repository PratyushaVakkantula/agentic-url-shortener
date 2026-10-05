#!/usr/bin/env bash
#
# End-to-end demo against a running instance (./mvnw spring-boot:run).
# Requires curl and jq. Every step goes through the public HTTP API, exactly as a client would.
#
#   ./scripts/demo.sh            # all sections
#   ./scripts/demo.sh brownfield # one section: shortener | greenfield | brownfield | ambiguous | governance | metrics
#
# Crash-recovery demo (see README):
#   ./scripts/demo.sh pause          # start a run and leave it waiting at a human checkpoint; prints the run id
#   kill -9 <server pid>; ./mvnw spring-boot:run
#   ./scripts/demo.sh approve <id>   # approve its checkpoints on the restarted server and finish the run
#
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
SECTION="${1:-all}"
RUN_ARG="${2:-}"

bold() { printf '\n\033[1m%s\033[0m\n' "$*"; }
step() { printf '  \033[36m▸\033[0m %s\n' "$*"; }
show() { printf '    %s\n' "$*"; }

# api USER METHOD PATH [JSON] → prints body. USER "-" = anonymous.
# (${auth[@]+...} keeps empty arrays safe under set -u on macOS bash 3.2.)
api() {
  local user="$1" method="$2" path="$3" body="${4:-}"
  local auth=()
  [[ "$user" != "-" ]] && auth=(-u "$user:$user-pass")
  if [[ -n "$body" ]]; then
    curl -sS ${auth[@]+"${auth[@]}"} -X "$method" "$BASE$path" -H 'Content-Type: application/json' -d "$body"
  else
    curl -sS ${auth[@]+"${auth[@]}"} -X "$method" "$BASE$path"
  fi
}

# status_of USER METHOD PATH [JSON] → HTTP status code only
status_of() {
  local user="$1" method="$2" path="$3" body="${4:-}"
  curl -s -o /dev/null -w '%{http_code}' -u "$user:$user-pass" -X "$method" "$BASE$path" \
       -H 'Content-Type: application/json' ${body:+-d "$body"}
}

start_run() { # WORKFLOW TITLE DESCRIPTION → runId
  api alice POST "/api/v1/workflows/$1/runs" "$(jq -n --arg t "$2" --arg d "$3" '{title:$t, description:$d}')" | jq -r .runId
}

run() { api bob GET "/api/v1/runs/$1"; }

# await_approval RUN STAGE [EXCLUDE_ID] → pending approval JSON for STAGE ("*" = first checkpoint, any stage)
await_approval() {
  local run_id="$1" stage="$2" exclude="${3:-none}"
  for _ in $(seq 1 300); do
    local a
    a=$(run "$run_id" | jq -c --arg s "$stage" --arg x "$exclude" \
        '[.approvals[] | select(.status=="PENDING" and ($s=="*" or .stageId==$s) and .approvalId!=$x)][0] // empty')
    [[ -n "$a" ]] && { echo "$a"; return; }
    sleep 0.1
  done
  echo "timed out waiting for approval on $stage" >&2; exit 1
}

approve() { # RUN APPROVAL_JSON [APPROVER]
  local id hash
  id=$(jq -r .approvalId <<<"$2"); hash=$(jq -r .artifact.contentHash <<<"$2")
  api "${3:-bob}" POST "/api/v1/runs/$1/approvals/$id" \
      "$(jq -n --arg h "$hash" '{decision:"APPROVE", artifactHash:$h, comment:"reviewed in demo"}')" >/dev/null
}

checkpoint() { # RUN STAGE → show why a human is asked, then approve as bob
  local a; a=$(await_approval "$1" "$2")
  step "checkpoint at '$2' — reasons:"
  jq -r '.reasons[] | "      • " + .' <<<"$a"
  approve "$1" "$a"
  show "approved by bob (artifact $(jq -r '.artifact.contentHash[0:12]' <<<"$a"))"
}

await_end() {
  for _ in $(seq 1 300); do
    local s; s=$(run "$1" | jq -r .status)
    [[ "$s" != "RUNNING" ]] && { echo "$s"; return; }
    sleep 0.1
  done
  echo "TIMEOUT"
}

summary() {
  run "$1" | jq -r '"    run \(.runId[0:8]) \(.workflow): \(.status) — \(.eventCount) audit events",
                    (.stages[] | "      \(.status | .[0:10] | (. + "          ")[0:10])  \(.id) (\(.agent), attempt \(.attempts))")'
}

# ---------------------------------------------------------------------------------------------

section_shortener() {
  bold "1. URL shortener"
  local link code
  link=$(api - POST /api/v1/urls '{"url":"https://docs.spring.io/spring-boot/","customAlias":"demo-docs"}' || true)
  code=$(jq -r '.code // empty' <<<"$link")
  [[ -z "$code" ]] && code="demo-docs" # already exists from a previous demo run
  step "created $BASE/$code"
  step "redirect: $(curl -s -o /dev/null -w '%{http_code} → %{redirect_url}' "$BASE/$code")"
  step "unsafe URL rejected: $(api - POST /api/v1/urls '{"url":"http://169.254.169.254/latest/meta-data"}' | jq -r .errorCode)"
  sleep 0.5
  step "analytics: $(api - GET "/api/v1/urls/$code/analytics?days=1" | jq -c '{totalClicks, browsers}')"
}

section_greenfield() {
  bold "2. Greenfield: new capability from a well-defined requirement"
  local id; id=$(start_run greenfield-feature "QR codes for short links" \
    "Users must be able to download a QR code for any short link. The QR image must be a PNG of 300x300 pixels. Requesting a QR code for an unknown link must return 404.")
  step "run $id started by alice"
  checkpoint "$id" implementation
  checkpoint "$id" release
  step "finished: $(await_end "$id")"
  summary "$id"
  step "task plan:"; run "$id" | jq -r '.artifacts[] | select(.stageId=="implementation") | .content.tasks[] | "      \(.id) \(.title)  ← needs \(.dependsOn)"' | tail -n 6
}

section_brownfield() {
  bold "3. Brownfield: change the existing shortener (static analysis of this repository)"
  local id; id=$(start_run brownfield-change "Add per-link click limits" \
    "Each short link can have an optional maximum number of clicks. Once the limit is reached, the link must stop redirecting and return 410 Gone. The limit must be set when the link is created.")
  step "run $id started"
  local first; first=$(await_approval "$id" implementation)
  step "impact analysis:"
  run "$id" | jq -r '.artifacts[] | select(.stageId=="impact-analysis") | .content |
      "      module \(.primaryModules), tables \(.tables), next migration \(.nextMigration), risk \(.riskLevel)",
      "      seeds \([.seeds[].type])", "      scanned \(.stats.typesScanned) types / \(.stats.dependencyEdges) edges"'
  step "design: $(run "$id" | jq -r '.artifacts[] | select(.stageId=="design") | .content.schemaChanges[0].statement')"
  step "checkpoint at 'implementation' — reasons:"; jq -r '.reasons[] | "      • " + .' <<<"$first"
  approve "$id" "$first"; show "approved by bob"
  checkpoint "$id" release
  step "finished: $(await_end "$id")"
  summary "$id"
}

section_ambiguous() {
  bold "4. Ambiguous: vague requirement → clarification → human revision → re-plan"
  local id; id=$(start_run ambiguous-requirement "Make the URL shortener faster and more reliable" "")
  step "run $id started"
  local first; first=$(await_approval "$id" clarification)
  step "agent's open questions:"
  run "$id" | jq -r '.artifacts[] | select(.stageId=="clarification") | .content.openQuestions[] | "      ? " + .'
  step "impact-analysis is $(run "$id" | jq -r '.stages[] | select(.id=="impact-analysis") | .status') — nothing is built on unconfirmed assumptions"

  step "product owner (alice) revises the requirements with concrete targets"
  api alice POST "/api/v1/runs/$id/stages/requirements/revisions" "$(jq -n '{
    reason: "product owner set concrete targets",
    content: {
      title: "Faster and more reliable redirects",
      statement: "Redirect p95 latency must be below 50 ms at 200 requests/s. Redirects must keep working when analytics storage is unavailable.",
      classification: "WELL_DEFINED", clarityScore: 1.0, needsClarification: false,
      acceptanceCriteria: [
        {id:"AC-1", text:"Redirect p95 latency must be below 50 ms at 200 requests/s", measurable:true},
        {id:"AC-2", text:"Redirects must keep working when analytics storage is unavailable", measurable:true}],
      ambiguities: [], assumptions: [],
      keywords: ["redirect","latency","click","analytic","storage"]}}')" | jq -r '"    revision stored as requirements v\(.version)"'

  local second; second=$(await_approval "$id" clarification "$(jq -r .approvalId <<<"$first")")
  step "re-planned: first checkpoint $(run "$id" | jq -r --arg a "$(jq -r .approvalId <<<"$first")" '.approvals[] | select(.approvalId==$a) | .status'), new checkpoint raised for the revised content"
  approve "$id" "$second"; show "approved by bob"
  checkpoint "$id" release
  step "finished: $(await_end "$id")"
  summary "$id"
}

section_governance() {
  bold "5. Governance guardrails (expected refusals)"
  local id; id=$(start_run brownfield-change "Add per-link click limits" "Each link can have an optional maximum number of clicks. The link must return 410 once reached.")
  local a; a=$(await_approval "$id" implementation)
  local path="/api/v1/runs/$id/approvals/$(jq -r .approvalId <<<"$a")"
  local good; good=$(jq -n --arg h "$(jq -r .artifact.contentHash <<<"$a")" '{decision:"APPROVE", artifactHash:$h}')
  local stale; stale=$(jq -n '{decision:"APPROVE", artifactHash:("0" * 64)}')
  step "alice (requester) approves:         HTTP $(status_of alice POST "$path" "$good")  (needs APPROVER role)"
  step "admin approves alice's run w/ stale hash: HTTP $(status_of admin POST "$path" "$stale")  (STALE_APPROVAL)"
  local own; own=$(api admin POST /api/v1/workflows/brownfield-change/runs '{"title":"Admin own run","description":"Links must expire after 30 days."}' | jq -r .runId)
  local oa; oa=$(await_approval "$own" "*")   # no migration here, so the first checkpoint is the release
  step "admin approves own run:              HTTP $(status_of admin POST "/api/v1/runs/$own/approvals/$(jq -r .approvalId <<<"$oa")" \
        "$(jq -n --arg h "$(jq -r .artifact.contentHash <<<"$oa")" '{decision:"APPROVE", artifactHash:$h}')")  (SELF_APPROVAL_FORBIDDEN)"
  step "bob safe-stops a run:                HTTP $(status_of bob POST "/api/v1/runs/$id/stop" '{"reason":"x"}')  (ADMIN only)"
  step "admin safe-stops both runs:          HTTP $(status_of admin POST "/api/v1/runs/$id/stop" '{"reason":"demo over"}') / $(status_of admin POST "/api/v1/runs/$own/stop" '{"reason":"demo over"}')"
  step "final status: $(await_end "$id") / $(await_end "$own")"
}

section_metrics() {
  bold "6. Reliability metrics (computed from the audit log)"
  api bob GET /api/v1/metrics | jq '{runs: .runs, retryRate: .stages.retryRate, firstPassYield: .stages.firstPassYield,
      rollbackFrequency: .recovery.rollbackFrequency, mttrMillis: .recovery.mttrMillis,
      latencyP50Millis: .latency.p50Millis, approvalWaitP50Millis: .latency.approvalWaitP50Millis,
      governance: .governance, replanning: .replanning}' | sed 's/^/    /'
}

section_pause() {
  bold "Crash-recovery demo, part 1: leave a run waiting for a human"
  local id; id=$(start_run brownfield-change "Add per-link click limits" \
    "Each short link can have an optional maximum number of clicks. Once the limit is reached, the link must return 410 Gone.")
  local a; a=$(await_approval "$id" "*")
  step "run $id is waiting at '$(jq -r .stageId <<<"$a")' (approval $(jq -r '.approvalId[0:8]' <<<"$a"))"
  step "now crash the server:   pkill -9 -f AgenticUrlShortenerApplication"
  step "start it again:         ./mvnw spring-boot:run   (log shows: Resumed run ${id:0:8}…)"
  step "then finish the run:    ./scripts/demo.sh approve $id"
}

section_approve() {
  [[ -z "$RUN_ARG" ]] && { echo "usage: $0 approve <runId>" >&2; exit 2; }
  bold "Crash-recovery demo, part 2: decide the open checkpoint(s) on the restarted server"
  step "run status after restart: $(run "$RUN_ARG" | jq -r .status)"
  # Approve whatever checkpoint is open until the run ends (it may raise more than one).
  for _ in $(seq 1 600); do
    local view; view=$(run "$RUN_ARG")
    [[ "$(jq -r .status <<<"$view")" != "RUNNING" ]] && break
    local a; a=$(jq -c '[.approvals[] | select(.status=="PENDING")][0] // empty' <<<"$view")
    if [[ -n "$a" ]]; then
      step "approving '$(jq -r .stageId <<<"$a")' (approval $(jq -r '.approvalId[0:8]' <<<"$a"))"
      approve "$RUN_ARG" "$a"
    fi
    sleep 0.1
  done
  step "finished: $(await_end "$RUN_ARG")"
  summary "$RUN_ARG"
  step "audit trail includes RunResumed: $(api bob GET "/api/v1/runs/$RUN_ARG/events" | jq '[.[].type] | index("RunResumed") != null')"
}

curl -sf "$BASE/actuator/health" >/dev/null || { echo "No app at $BASE — start it with ./mvnw spring-boot:run" >&2; exit 1; }
case "$SECTION" in
  all) section_shortener; section_greenfield; section_brownfield; section_ambiguous; section_governance; section_metrics ;;
  shortener|greenfield|brownfield|ambiguous|governance|metrics|pause|approve) "section_$SECTION" ;;
  *) echo "unknown section '$SECTION'" >&2; exit 2 ;;
esac
bold "Done. Swagger UI: $BASE/swagger-ui.html"
