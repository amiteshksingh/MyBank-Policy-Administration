#!/usr/bin/env bash
 
set -euo pipefail
 
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "${SCRIPT_DIR}/.." && pwd)"
DATA_ROOT="${APP_HOME}/data"
LOG_ROOT="${APP_HOME}/log"
ARCHIVE_ROOT="${LOG_ROOT}/archive"
PID_FILE="${DATA_ROOT}/data_processor.pid"
 
BATCH_SIZE=1000
CONTINUE_ON_ERROR=false
COMPRESS_ARTIFACTS=true
SKIP_BACKUP=false
BASE_URL="http://localhost:8080"
HEALTH_PATH="/actuator/health"
JAR_PATH="${APP_HOME}/target/data_processor-0.0.1-SNAPSHOT.jar"
PREFERENCE=""
INPUT_FILE=""
ENV_FILE=""
RESUME_RUN_DIR=""
APP_PID=""
total_uuids_processed=0
total_batches_success=0
total_batches_failed=0
run_start_epoch=0
RUN_TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_DIR=""
SPLIT_DIR=""
COMPLETED_BATCHES_FILE=""
ORCHESTRATOR_PID_FILE="${DATA_ROOT}/data_processor_orchestrator.pid"
ORCHESTRATOR_LOG="${LOG_ROOT}/mfabulk_orchestrator_${RUN_TIMESTAMP}.log"
APP_LOG="${LOG_ROOT}/mfabulk_app_${RUN_TIMESTAMP}.log"
FCLA_BACKUP_LOG="${LOG_ROOT}/mfabulk_fcla_backup_${RUN_TIMESTAMP}.log"
PAIC_BACKUP_LOG="${LOG_ROOT}/mfabulk_paic_backup_${RUN_TIMESTAMP}.log"
SYNC_LOG="${LOG_ROOT}/mfabulk_sync_${RUN_TIMESTAMP}.log"
FAILED_UUIDS_LOG="${LOG_ROOT}/mfabulk_failed_uuids_${RUN_TIMESTAMP}.log"
SUCCESS_UUIDS_LOG="${LOG_ROOT}/mfabulk_success_uuids_${RUN_TIMESTAMP}.log"
UUID_STATUS_LOG="${LOG_ROOT}/mfabulk_uuid_status_${RUN_TIMESTAMP}.tsv"
total_failed_uuids=0
batch_failed=0
 
log_message() {
  local message="$1"
  mkdir -p "${LOG_ROOT}"
  echo "$(date -Is): ${message}" | tee -a "${ORCHESTRATOR_LOG}"
}
 
usage() {
  cat <<'EOF'
Usage: run_mfa_bulk_sync.sh --input-file <path> --preference <EMAIL|SMS> [options]
 
Options:
  --input-file <path>      Required. File containing one UUID per line.
  --preference <EMAIL|SMS> Required. MFA preference to sync (EMAIL or SMS).
  --batch-size <count>     Optional. UUIDs per script batch. Default: 1000. No upper limit — the service handles Oracle's 1000-row IN clause limit internally.
  --env-file <path>        Optional. Shell env file to source before startup.
  --resume-run-dir <path>  Optional. Resume a prior run directory and skip completed batches.
  --jar-path <path>        Spring Boot jar to launch.
  --base-url <url>         Local application base URL.
  --health-path <path>     Health endpoint path. Default: /actuator/health
  --continue-on-error      Optional. Log sync failures and continue instead of exiting.
  --skip-backup            Optional. Skip PAIC/FCLA before-state snapshots (faster; no rollback data).
  --no-compress            Optional. Disable gzip compression of batch artifacts (default: compress).
  --help                   Show this help text.
EOF
}
 
initialize_run_paths() {
  if [[ -n "${RESUME_RUN_DIR}" ]]; then
    RUN_DIR="${RESUME_RUN_DIR}"
  else
    RUN_DIR="${DATA_ROOT}/mfabulk_run_${RUN_TIMESTAMP}"
  fi
  SPLIT_DIR="${RUN_DIR}/split"
  COMPLETED_BATCHES_FILE="${RUN_DIR}/completed_batches.txt"
}
 
load_env_file() {
  local env_path="$1"
  if [[ ! -f "${env_path}" ]]; then
    echo "Environment file not found: ${env_path}" >&2
    exit 1
  fi
  set -a
  # shellcheck disable=SC1090
  . "${env_path}"
  set +a
}
 
require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: ${name}" >&2
    exit 1
  fi
}
 
archive_logs() {
  mkdir -p "${ARCHIVE_ROOT}"
  shopt -s nullglob
  local existing=("${LOG_ROOT}"/*.log)
  shopt -u nullglob
  if (( ${#existing[@]} > 0 )); then
    local target_dir="${ARCHIVE_ROOT}/${RUN_TIMESTAMP}"
    mkdir -p "${target_dir}"
    mv "${existing[@]}" "${target_dir}/"
  fi
}
 
stop_pid_if_running() {
  local pid="$1"
  if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
    kill "${pid}" 2>/dev/null || true
    local i=0
    while kill -0 "${pid}" 2>/dev/null && (( i < 10 )); do
      sleep 1
      i=$((i + 1))
    done
    if kill -0 "${pid}" 2>/dev/null; then
      log_message "Process ${pid} still alive after ${i}s — sending SIGKILL"
      kill -9 "${pid}" 2>/dev/null || true
    fi
    wait "${pid}" 2>/dev/null || true
  fi
}
 
stop_existing_app() {
  if [[ -f "${PID_FILE}" ]]; then
    local prior_pid
    prior_pid="$(cat "${PID_FILE}")"
    stop_pid_if_running "${prior_pid}"
    rm -f "${PID_FILE}"
  fi
}
 
wait_for_app() {
  local attempts=0
  local health_url="${BASE_URL%/}${HEALTH_PATH}"
  log_message "Waiting for application to be UP at ${health_url}"
  while true; do
    local response
    response=$(curl -fsS "${health_url}" 2>/dev/null || true)
    if echo "${response}" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
      log_message "Application is UP"
      break
    fi
    attempts=$((attempts + 1))
    if (( attempts > 20 )); then
      log_message "ERROR: Application health check did not report UP after $((attempts * 2))s at ${health_url}"
      echo "Application health check did not report UP at ${health_url}" >&2
      exit 1
    fi
    if (( attempts % 5 == 0 )); then
      log_message "Still waiting for application... attempt ${attempts}/20"
    fi
    sleep 2
  done
}
 
start_app() {
  mkdir -p "${LOG_ROOT}" "${DATA_ROOT}"
  local java_cmd="${JAVA_CMD:-java}"
  # JAVA_OPTS allows callers to inject proxy settings and other JVM flags.
  # e.g. export JAVA_OPTS="-Dhttps.proxyHost=proxy.infosec.fedex.com -Dhttps.proxyPort=443"
  # shellcheck disable=SC2086
  nohup "${java_cmd}" ${JAVA_OPTS:-} -jar "${JAR_PATH}" >"${APP_LOG}" 2>&1 &
  APP_PID=$!
  echo "${APP_PID}" > "${PID_FILE}"
  log_message "Application process launched with pid=${APP_PID}, log=${APP_LOG}"
  wait_for_app
}
 
json_from_file() {
  local source_file="$1"
  local target_file="$2"
  # Fast: strip nulls, blanks/CR, quote each line, join with commas, wrap in brackets
  tr -d '\0' < "${source_file}" \
    | sed '/^[[:space:]]*$/d; s/\r//g; s/\\/\\\\/g; s/"/\\"/g; s/.*/ "&"/' \
    | paste -sd',' \
    | sed 's/^/[/; s/$/]/' > "${target_file}"
}
 
request_mfa_sync_body() {
  local uuid_json_file="$1"
  local target_file="$2"
  printf '{"uuids":' > "${target_file}"
  cat "${uuid_json_file}" >> "${target_file}"
  printf ',"preference":"%s"}' "$PREFERENCE" >> "${target_file}"
}
 
# Compress completed batch artifacts to reclaim disk space.
# Gzips the large JSON files in-place; keeps the tiny uuid batch text file.
compress_batch_artifacts() {
  local dir="$1"
  [[ "${COMPRESS_ARTIFACTS}" == "true" ]] || return 0
  gzip -f \
    "${dir}/fcla_source.json" \
    "${dir}/fcla_before.json" \
    "${dir}/paic_before.json" \
    "${dir}/sync_result.json" \
    "${dir}/uuids.json" 2>/dev/null || true
}
 
# Rotate the app log to prevent it growing unbounded during a long run.
# Copies current content, compresses it, then truncates the live file.
rotate_app_log() {
  [[ "${COMPRESS_ARTIFACTS}" == "true" ]] || return 0
  [[ -f "${APP_LOG}" ]] || return 0
  local rotated="${APP_LOG}.$(date -u +%Y%m%dT%H%M%SZ).gz"
  cp "${APP_LOG}" "${rotated}.tmp" 2>/dev/null || return 0
  gzip -f "${rotated}.tmp" 2>/dev/null && mv "${rotated}.tmp.gz" "${rotated}" 2>/dev/null || true
  # Truncate (not remove) so the JVM file handle stays valid.
  : > "${APP_LOG}"
  log_message "App log rotated to ${rotated}"
}
 
print_summary() {
  local run_elapsed
  if (( run_start_epoch > 0 )); then
    run_elapsed=$(( $(date +%s) - run_start_epoch ))
  else
    run_elapsed=0
  fi
  log_message "========================================="
  log_message "RUN SUMMARY"
  log_message "  Total UUIDs processed: ${total_uuids_processed}"
  log_message "  UUIDs synced successfully: $(( total_uuids_processed - total_failed_uuids ))"
  log_message "  UUIDs with PAIC failures:  ${total_failed_uuids}"
  log_message "  Total batches:         $((total_batches_success + total_batches_failed))"
  log_message "  Batches successful:    ${total_batches_success}"
  log_message "  Batches failed:        ${total_batches_failed}"
  log_message "  Total elapsed:         ${run_elapsed}s"
  log_message "  Artifacts:             ${RUN_DIR:-<not set>}"
  log_message "  FCLA backup log:       ${FCLA_BACKUP_LOG}"
  log_message "  PAIC backup log:       ${PAIC_BACKUP_LOG}"
  log_message "  Sync log:              ${SYNC_LOG}"
  log_message "  Failed UUIDs log:      ${FAILED_UUIDS_LOG}"
  log_message "  Success UUIDs log:     ${SUCCESS_UUIDS_LOG}"
  log_message "  UUID status file:      ${UUID_STATUS_LOG}"
  log_message "========================================="
}
 
cleanup() {
  print_summary
  stop_pid_if_running "${APP_PID:-}"
  rm -f "${PID_FILE}" "${ORCHESTRATOR_PID_FILE}"
}
 
is_completed_batch() {
  local batch_name="$1"
  [[ -f "${COMPLETED_BATCHES_FILE}" ]] && grep -Fxq "${batch_name}" "${COMPLETED_BATCHES_FILE}"
}
 
mark_batch_completed() {
  local batch_name="$1"
  printf '%s\n' "${batch_name}" >> "${COMPLETED_BATCHES_FILE}"
}
 
prepare_batch_inputs() {
  if [[ -n "${RESUME_RUN_DIR}" ]]; then
    if [[ ! -d "${RUN_DIR}" ]]; then
      echo "Resume run directory not found: ${RUN_DIR}" >&2
      exit 1
    fi
    if [[ ! -d "${SPLIT_DIR}" ]]; then
      echo "Split directory not found under resume run directory: ${SPLIT_DIR}" >&2
      exit 1
    fi
    touch "${COMPLETED_BATCHES_FILE}"
    log_message "Resuming prior run from ${RUN_DIR}"
    return
  fi
 
  if [[ ! -f "${INPUT_FILE}" ]]; then
    echo "Input file not found: ${INPUT_FILE}" >&2
    exit 1
  fi
 
  mkdir -p "${RUN_DIR}" "${SPLIT_DIR}"
  CLEAN_INPUT="${RUN_DIR}/uuids.cleaned.txt"
  tr -d '\r\0' < "${INPUT_FILE}" | sed '/^[[:space:]]*$/d' > "${CLEAN_INPUT}"
  split -l "${BATCH_SIZE}" -d -a 8 "${CLEAN_INPUT}" "${SPLIT_DIR}/uuids_batch_"
  : > "${COMPLETED_BATCHES_FILE}"
  log_message "Input split into $(find "${SPLIT_DIR}" -maxdepth 1 -type f | wc -l | tr -d ' ') batch file(s)"
}
 
ORIGINAL_ARGS=("$@")
 
while [[ $# -gt 0 ]]; do
  case "$1" in
    --input-file)
      INPUT_FILE="$2"
      shift 2
      ;;
    --preference)
      PREFERENCE="$2"
      shift 2
      ;;
    --env-file)
      ENV_FILE="$2"
      shift 2
      ;;
    --resume-run-dir)
      RESUME_RUN_DIR="$2"
      shift 2
      ;;
    --jar-path)
      JAR_PATH="$2"
      shift 2
      ;;
    --batch-size)
      BATCH_SIZE="$2"
      shift 2
      ;;
    --base-url)
      BASE_URL="$2"
      shift 2
      ;;
    --health-path)
      HEALTH_PATH="$2"
      shift 2
      ;;
    --continue-on-error)
      CONTINUE_ON_ERROR=true
      shift
      ;;
    --skip-backup)
      SKIP_BACKUP=true
      shift
      ;;
    --no-compress)
      COMPRESS_ARTIFACTS=false
      shift
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done
 
initialize_run_paths
 
if [[ -z "${INPUT_FILE}" && -z "${RESUME_RUN_DIR}" ]]; then
  usage
  exit 1
fi
if [[ -z "${PREFERENCE}" ]]; then
  echo "Missing required --preference argument (EMAIL or SMS)" >&2
  usage
  exit 1
fi
if [[ "${PREFERENCE}" != "EMAIL" && "${PREFERENCE}" != "SMS" ]]; then
  echo "Invalid value for --preference: ${PREFERENCE}. Must be EMAIL or SMS." >&2
  usage
  exit 1
fi
if (( BATCH_SIZE < 1 )); then
  echo "batch-size must be at least 1" >&2
  exit 1
fi
 
if [[ -n "${ENV_FILE}" ]]; then
  load_env_file "${ENV_FILE}"
elif [[ -f "${APP_HOME}/data/data_processor.env" ]]; then
  ENV_FILE="${APP_HOME}/data/data_processor.env"
  load_env_file "${ENV_FILE}"
fi
 
require_env FCLA_DATABASE_URL
require_env FCLA_DATABASE_APP_USER
require_env FCLA_DATABASE_APP_PASSWORD
require_env PAIC_HOSTNAME
require_env PAIC_JWK_PATH
require_env PAIC_SVC_ACCT_ID
 
# ── nohup self-detach ─────────────────────────────────────────────────────────
# If stdin is a terminal the user ran the script directly from an interactive
# shell (PuTTY, SSH, etc.).  Re-exec under nohup so the process survives the
# terminal disconnecting.  Print the PID and the orchestrator log path so the
# user can monitor progress with:  tail -f <log>
if [[ -t 0 ]]; then
  mkdir -p "${LOG_ROOT}"
  NOHUP_LOG="${ORCHESTRATOR_LOG}"
  echo "Detaching to background.  Monitor with:"
  echo "  tail -f ${NOHUP_LOG}"
  echo "Kill with:"
  echo "  ${SCRIPT_DIR}/kill_data_processor.sh"
  nohup "$0" "${ORIGINAL_ARGS[@]}" < /dev/null > "${NOHUP_LOG}" 2>&1 &
  echo "Background PID: $!"
  exit 0
fi
 
mkdir -p "${RUN_DIR}" "${LOG_ROOT}" "${DATA_ROOT}"
archive_logs
trap cleanup EXIT
 
# Write orchestrator PID so kill_data_processor.sh can stop this script too.
echo $$ > "${ORCHESTRATOR_PID_FILE}"
 
# Write UUID status log header
echo -e "uuid\tpaic_status\tfcl_status" > "${UUID_STATUS_LOG}"
 
log_message "Starting ${PREFERENCE} MFA bulk sync"
log_message "Input file=${INPUT_FILE} base_url=${BASE_URL} health_path=${HEALTH_PATH} jar_path=${JAR_PATH} preference=${PREFERENCE} batch_size=${BATCH_SIZE} continue_on_error=${CONTINUE_ON_ERROR} skip_backup=${SKIP_BACKUP}"
if [[ -n "${ENV_FILE}" ]]; then
  log_message "Loaded environment from ${ENV_FILE}"
fi
 
# Split input first so disk I/O completes while app starts in background
prepare_batch_inputs
stop_existing_app
start_app
log_message "Application started with pid=${APP_PID}"
 
run_start_epoch=$(date +%s)
log_message "Separate log files: fcla_backup=${FCLA_BACKUP_LOG} paic_backup=${PAIC_BACKUP_LOG} sync=${SYNC_LOG}"
 
batch_number=0
for batch_file in "${SPLIT_DIR}"/uuids_batch_*; do
  [[ -f "${batch_file}" ]] || continue
  batch_name="$(basename "${batch_file}")"
  if is_completed_batch "${batch_name}"; then
    log_message "Skipping completed batch file ${batch_name}"
    continue
  fi
  batch_number=$((batch_number + 1))
  batch_suffix="${batch_name#uuids_batch_}"
  batch_dir="${RUN_DIR}/batch_${batch_suffix}"
  mkdir -p "${batch_dir}"
  uuid_count=$(awk 'END{print NR}' "${batch_file}")
  total_uuids_processed=$((total_uuids_processed + uuid_count))
  log_message "=== BATCH ${batch_number} (${batch_name}) START === ${uuid_count} UUIDs (running total: ${total_uuids_processed})"
  batch_start_epoch=$(date +%s)
 
  uuid_json="${batch_dir}/uuids.json"
  sync_body="${batch_dir}/sync_request.json"
  json_from_file "${batch_file}" "${uuid_json}"
  request_mfa_sync_body "${uuid_json}" "${sync_body}"
 
  # --- PAIC BACKUP (parallel) ---
  if [[ "${SKIP_BACKUP}" != "true" ]]; then
    log_message "Batch ${batch_number}: Capturing PAIC before-state (${uuid_count} UUIDs)"
    curl -fsS --max-time 600 -X POST \
      -H 'Content-Type: application/json' \
      --data-binary @"${uuid_json}" \
      "${BASE_URL}/paic/query" > "${batch_dir}/paic_before.json" &
    paic_pid=$!
 
    # --- FCLA LOGIN BACKUP (parallel) ---
    log_message "Batch ${batch_number}: Capturing FCLA LOGIN before-state (${uuid_count} UUIDs)"
    fcla_login_ok=true
    curl -fsS --max-time 120 -X POST \
      -H 'Content-Type: application/json' \
      --data-binary @"${uuid_json}" \
      "${BASE_URL}/fcladb/query" > "${batch_dir}/fcla_source.json" &
    fcla_login_pid=$!
 
    # --- FCLA MFA PREFERENCES BACKUP (parallel) ---
    log_message "Batch ${batch_number}: Capturing FCLA MFA before-state (${uuid_count} UUIDs)"
    curl -fsS --max-time 600 -X POST \
      -H 'Content-Type: application/json' \
      --data-binary @"${uuid_json}" \
      "${BASE_URL}/fcladb/mfa-preferences/query?preference=${PREFERENCE}" > "${batch_dir}/fcla_before.json" &
    fcla_pid=$!
 
    paic_ok=true
    wait "${paic_pid}" || paic_ok=false
    if [[ "${paic_ok}" == "true" ]]; then
      paic_record_count="unknown"
      if [[ -f "${batch_dir}/paic_before.json" ]]; then
        paic_record_count=$(grep -o '"frIndexedString1"' "${batch_dir}/paic_before.json" 2>/dev/null | wc -l | tr -d ' ' || echo 0)
      fi
      log_message "Batch ${batch_number}: PAIC backup complete: ${paic_record_count} identities"
      echo "$(date -Is): Batch ${batch_number}: ${paic_record_count} identities captured -> ${batch_dir}/paic_before.json" >> "${PAIC_BACKUP_LOG}"
    else
      log_message "WARN: PAIC before-state capture failed for batch ${batch_name}; snapshot skipped, sync will continue"
      echo "$(date -Is): Batch ${batch_number}: PAIC backup FAILED" >> "${PAIC_BACKUP_LOG}"
    fi
 
    wait "${fcla_login_pid}" || fcla_login_ok=false
    if [[ "${fcla_login_ok}" == "true" ]]; then
      fcla_login_count="unknown"
      if [[ -f "${batch_dir}/fcla_source.json" ]]; then
        fcla_login_count=$(grep -o '"uuidNbr"' "${batch_dir}/fcla_source.json" 2>/dev/null | wc -l | tr -d ' ' || echo 0)
      fi
      log_message "Batch ${batch_number}: FCLA LOGIN backup complete: ${fcla_login_count} records"
      echo "$(date -Is): Batch ${batch_number}: ${fcla_login_count} records captured -> ${batch_dir}/fcla_source.json" >> "${FCLA_BACKUP_LOG}"
    else
      log_message "WARN: FCLA LOGIN before-state capture failed for batch ${batch_name}; snapshot skipped, sync will continue"
      echo "$(date -Is): Batch ${batch_number}: FCLA LOGIN backup FAILED" >> "${FCLA_BACKUP_LOG}"
    fi
 
    fcla_ok=true
    wait "${fcla_pid}" || fcla_ok=false
    if [[ "${fcla_ok}" == "true" ]]; then
      log_message "Batch ${batch_number}: FCLA MFA backup complete: ${uuid_count} UUIDs"
      echo "$(date -Is): Batch ${batch_number}: ${uuid_count} UUIDs captured -> ${batch_dir}/fcla_before.json" >> "${FCLA_BACKUP_LOG}"
    else
      log_message "WARN: FCLA MFA before-state capture failed for batch ${batch_name}; snapshot skipped, sync will continue"
      echo "$(date -Is): Batch ${batch_number}: FCLA MFA backup FAILED" >> "${FCLA_BACKUP_LOG}"
    fi
  else
    log_message "Batch ${batch_number}: Skipping before-state snapshots (--skip-backup)"
  fi
 
  # --- SYNC ---
  sync_start=$(date +%s)
  log_message "Batch ${batch_number}: Starting MFA sync (${uuid_count} UUIDs)"
  sync_http_code=$(curl -sS --max-time 600 -X POST \
    -H 'Content-Type: application/json' \
    --data-binary @"${sync_body}" \
    -o "${batch_dir}/sync_result.json" \
    -w "%{http_code}" \
    "${BASE_URL}/sync/mfa")
  sync_elapsed=$(( $(date +%s) - sync_start ))
  batch_elapsed=$(( $(date +%s) - batch_start_epoch ))
 
  if [[ "${sync_http_code}" == 2?? ]]; then
    # Parse paicFailedCount from response to detect partial PAIC failures
    paic_failed_count=0
    if [[ -f "${batch_dir}/sync_result.json" ]]; then
      if command -v python3 &>/dev/null; then
        paic_failed_count=$(python3 -c "
import json
try:
    data = json.load(open('${batch_dir}/sync_result.json'))
    print(data.get('paicFailedCount', 0))
except Exception:
    print(0)
" 2>/dev/null || echo 0)
      elif command -v jq &>/dev/null; then
        paic_failed_count=$(jq -r '.paicFailedCount // 0' "${batch_dir}/sync_result.json" 2>/dev/null || echo 0)
      else
        paic_failed_count=$(grep -o '"paicFailedCount":[0-9]*' "${batch_dir}/sync_result.json" 2>/dev/null | grep -o '[0-9]*$' || echo 0)
      fi
    fi
 
    # Extract per-UUID status into the master status TSV and legacy success log
    if [[ -f "${batch_dir}/sync_result.json" ]]; then
      if command -v python3 &>/dev/null; then
        python3 -c "
import json, sys
try:
    data = json.load(open('${batch_dir}/sync_result.json'))
    for u in data.get('fclaUpsertedUuids', []):
        print(str(u) + '\tPAIC_OK\tFCL_UPSERTED')
    for u in data.get('fclaNotUpsertedUuids', []):
        print(str(u) + '\tPAIC_OK\tFCL_NOT_IN_LOGIN')
    for u in data.get('paicFailedUuids', []):
        print(str(u) + '\tPAIC_FAILED\tFCL_SKIPPED')
except Exception as e:
    print('ERROR:' + str(e), file=sys.stderr)
" >> "${UUID_STATUS_LOG}" 2>/dev/null || true
        # Legacy success log: just the FCL_UPSERTED ones
        python3 -c "
import json
try:
    data = json.load(open('${batch_dir}/sync_result.json'))
    for u in data.get('fclaUpsertedUuids', []):
        print(str(u) + '\tFCLA_UPSERTED')
except Exception:
    pass
" >> "${SUCCESS_UUIDS_LOG}" 2>/dev/null || true
      elif command -v jq &>/dev/null; then
        jq -r '(.fclaUpsertedUuids[]? | . + "\tPAIC_OK\tFCL_UPSERTED"), (.fclaNotUpsertedUuids[]? | . + "\tPAIC_OK\tFCL_NOT_IN_LOGIN"), (.paicFailedUuids[]? | . + "\tPAIC_FAILED\tFCL_SKIPPED")' "${batch_dir}/sync_result.json" >> "${UUID_STATUS_LOG}" 2>/dev/null || true
        jq -r '.fclaUpsertedUuids[]? | . + "\tFCLA_UPSERTED"' "${batch_dir}/sync_result.json" >> "${SUCCESS_UUIDS_LOG}" 2>/dev/null || true
      else
        grep -o '"fclaUpsertedUuids":\[[^]]*\]' "${batch_dir}/sync_result.json" 2>/dev/null \
          | grep -oE '"[^"]+"' | tr -d '"' \
          | while IFS= read -r u; do printf '%s\tPAIC_OK\tFCL_UPSERTED\n' "${u}"; done >> "${UUID_STATUS_LOG}" || true
        grep -o '"fclaNotUpsertedUuids":\[[^]]*\]' "${batch_dir}/sync_result.json" 2>/dev/null \
          | grep -oE '"[^"]+"' | tr -d '"' \
          | while IFS= read -r u; do printf '%s\tPAIC_OK\tFCL_NOT_IN_LOGIN\n' "${u}"; done >> "${UUID_STATUS_LOG}" || true
        grep -o '"paicFailedUuids":\[[^]]*\]' "${batch_dir}/sync_result.json" 2>/dev/null \
          | grep -oE '"[^"]+"' | tr -d '"' \
          | while IFS= read -r u; do printf '%s\tPAIC_FAILED\tFCL_SKIPPED\n' "${u}"; done >> "${UUID_STATUS_LOG}" || true
        grep -o '"fclaUpsertedUuids":\[[^]]*\]' "${batch_dir}/sync_result.json" 2>/dev/null \
          | grep -oE '"[^"]+"' | tr -d '"' \
          | while IFS= read -r u; do printf '%s\tFCLA_UPSERTED\n' "${u}"; done >> "${SUCCESS_UUIDS_LOG}" || true
      fi
    fi
 
    if (( paic_failed_count > 0 )); then
      total_failed_uuids=$((total_failed_uuids + paic_failed_count))
      log_message "WARN: Batch ${batch_number}: /sync/mfa returned HTTP ${sync_http_code} but ${paic_failed_count} UUID(s) failed PAIC update. FCLA was skipped for those UUIDs."
      echo "$(date -Is): Batch ${batch_number}: HTTP ${sync_http_code} with ${paic_failed_count} PAIC failures in ${sync_elapsed}s -> ${batch_dir}/sync_result.json" >> "${SYNC_LOG}"
      # Extract and log the failed UUIDs
      if command -v python3 &>/dev/null; then
        python3 -c "
import json
try:
    data = json.load(open('${batch_dir}/sync_result.json'))
    for u in data.get('paicFailedUuids', []):
        print(str(u) + '\tPAIC_FAILED')
except Exception:
    pass
" >> "${FAILED_UUIDS_LOG}" 2>/dev/null || true
      elif command -v jq &>/dev/null; then
        jq -r '.paicFailedUuids[]? | . + "\tPAIC_FAILED"' "${batch_dir}/sync_result.json" >> "${FAILED_UUIDS_LOG}" 2>/dev/null || true
      else
        grep -o '"paicFailedUuids":\[[^]]*\]' "${batch_dir}/sync_result.json" 2>/dev/null \
          | grep -oE '"[^"]+"' | tr -d '"' \
          | while IFS= read -r u; do printf '%s\tPAIC_FAILED\n' "${u}"; done \
          >> "${FAILED_UUIDS_LOG}" || true
      fi
    else
      echo "$(date -Is): Batch ${batch_number}: HTTP ${sync_http_code}, ${uuid_count} UUIDs synced in ${sync_elapsed}s -> ${batch_dir}/sync_result.json" >> "${SYNC_LOG}"
      log_message "Batch ${batch_number}: MFA sync complete: HTTP ${sync_http_code} in ${sync_elapsed}s (0 errors)"
    fi
    total_batches_success=$((total_batches_success + 1))
  elif [[ "${sync_http_code}" == "422" ]]; then
    # 422 = some UUIDs failed PAIC update (e.g. not found in PAIC). Log them and continue.
    total_batches_failed=$((total_batches_failed + 1))
    log_message "WARN: Batch ${batch_number}: /sync/mfa returned HTTP 422 in ${sync_elapsed}s — some UUIDs failed PAIC update. Logging failed UUIDs and continuing."
    echo "$(date -Is): Batch ${batch_number}: HTTP 422 in ${sync_elapsed}s -> ${batch_dir}/sync_result.json" >> "${SYNC_LOG}"
    # Extract failed UUIDs from 422 response detail
    if [[ -f "${batch_dir}/sync_result.json" ]]; then
      batch_failed=0
      prev_log_lines=$(wc -l < "${FAILED_UUIDS_LOG}" 2>/dev/null | tr -d ' ' || echo 0)
      # Try python3 first for reliable JSON parsing
      python3 -c "
import json, re
try:
    with open('${batch_dir}/sync_result.json') as f:
        data = json.load(f)
    detail = data.get('detail', '')
    uuids = re.findall(r'[A-Za-z0-9_-]{8,}', detail)
    for u in uuids:
        print(u + '\t422_PAIC')
except Exception:
    pass
" >> "${FAILED_UUIDS_LOG}" 2>/dev/null || \
        grep -oE '[A-Za-z0-9_-]{8,}' "${batch_dir}/sync_result.json" >> "${FAILED_UUIDS_LOG}" 2>/dev/null || true
      new_log_lines=$(wc -l < "${FAILED_UUIDS_LOG}" 2>/dev/null | tr -d ' ' || echo 0)
      batch_failed=$(( new_log_lines - prev_log_lines ))
      total_failed_uuids=$(( total_failed_uuids + batch_failed ))
      log_message "Batch ${batch_number}: ${batch_failed} UUID(s) logged to failed UUIDs log"
    fi
  else
    total_batches_failed=$((total_batches_failed + 1))
    log_message "ERROR: Batch ${batch_number}: /sync/mfa returned HTTP ${sync_http_code} in ${sync_elapsed}s. See ${batch_dir}/sync_result.json"
    echo "$(date -Is): Batch ${batch_number}: FAILED HTTP ${sync_http_code} in ${sync_elapsed}s -> ${batch_dir}/sync_result.json" >> "${SYNC_LOG}"
    # Write all UUIDs from this batch to the failed UUIDs log
    local_batch_failed_count=0
    while IFS= read -r uuid; do
      [[ -z "${uuid}" ]] && continue
      printf '%s\tBATCH_FAILED_HTTP_%s\n' "${uuid}" "${sync_http_code}" >> "${FAILED_UUIDS_LOG}"
      local_batch_failed_count=$((local_batch_failed_count + 1))
    done < "${batch_file}"
    total_failed_uuids=$((total_failed_uuids + local_batch_failed_count))
    log_message "Batch ${batch_number}: ${local_batch_failed_count} UUID(s) logged to failed UUIDs log (HTTP ${sync_http_code})"
    # Do NOT mark batch as completed — allows --resume-run-dir to retry it
    if [[ "${CONTINUE_ON_ERROR}" != "true" ]]; then
      exit 1
    fi
    continue
  fi
 
  mark_batch_completed "${batch_name}"
  compress_batch_artifacts "${batch_dir}"
  log_message "=== BATCH ${batch_number} END === ${uuid_count} UUIDs in ${batch_elapsed}s (sync: ${sync_elapsed}s) | running: ${total_uuids_processed} UUIDs, ${total_batches_success} ok, ${total_batches_failed} failed"
 
  # Rotate the app log every 50 batches to prevent it growing unbounded.
  if (( batch_number % 50 == 0 )); then
    rotate_app_log
  fi
done
 
log_message "${PREFERENCE} MFA run artifacts written to ${RUN_DIR}"
 
# Archive the completed run directory into a single tar.gz to reclaim disk space.
# Only done on natural completion — aborted runs keep their directory intact for resume.
if [[ "${COMPRESS_ARTIFACTS}" == "true" && -d "${RUN_DIR}" ]]; then
  archive_file="${RUN_DIR}.tar.gz"
  log_message "Archiving run artifacts: ${RUN_DIR} -> ${archive_file}"
  if tar -czf "${archive_file}" -C "$(dirname "${RUN_DIR}")" "$(basename "${RUN_DIR}")" 2>/dev/null; then
    rm -rf "${RUN_DIR}"
    log_message "Run artifacts archived to ${archive_file} ($(du -sh "${archive_file}" 2>/dev/null | cut -f1) on disk)"
  else
    log_message "WARN: Archive failed — run directory preserved at ${RUN_DIR}"
  fi
fi