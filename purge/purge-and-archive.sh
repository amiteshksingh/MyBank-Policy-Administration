#!/usr/bin/env bash

set -euo pipefail
trap 'echo "ERROR at line $LINENO, exit code=$?" | tee -a error.log' ERR

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
#APP_HOME="$(cd "${SCRIPT_DIR}/.." && pwd)"
APP_HOME="/home/mydir/MyBank-Policy-Administration"
DATA_ROOT="${APP_HOME}/data"
LOG_ROOT="${APP_HOME}/log"
ARCHIVE_ROOT="${LOG_ROOT}/archive"
PID_FILE="${DATA_ROOT}/data_processor.pid"
ORCHESTRATOR_PID_FILE="${DATA_ROOT}/data_processor_orchestrator.pid"

# default wait = 40 seconds
STARTUP_TIMEOUT=40
# default cleanup = true
CLEANUP_APP=true

BATCH_SIZE=1000
CONTINUE_ON_ERROR=false
COMPRESS_ARTIFACTS=true
SKIP_BACKUP=false
BASE_URL="http://localhost:8080"
HEALTH_PATH="/actuator/health"
DELETE_PATH="/delete"
HTTP_METHOD="POST"
REQUEST_FORMAT="object"
#JAR_PATH="${APP_HOME}/target/data_processor-0.0.1-SNAPSHOT.jar"
JAR_PATH="${APP_HOME}/target/mybank-policy-administration.jar"
INPUT_FILE=""
ENV_FILE=""
RESUME_RUN_DIR=""
APP_PID=""
total_uuids_processed=0
total_uuids_deleted=0
total_failed_uuids=0
total_batches_success=0
total_batches_failed=0
run_start_epoch=0
RUN_TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_DIR=""
SPLIT_DIR=""
COMPLETED_BATCHES_FILE=""
ORCHESTRATOR_LOG="${LOG_ROOT}/delete_orchestrator_${RUN_TIMESTAMP}.log"
APP_LOG="${LOG_ROOT}/delete_app_${RUN_TIMESTAMP}.log"
FCLA_BACKUP_LOG="${LOG_ROOT}/delete_fcla_backup_${RUN_TIMESTAMP}.log"
PAIC_BACKUP_LOG="${LOG_ROOT}/delete_paic_backup_${RUN_TIMESTAMP}.log"
DELETE_LOG="${LOG_ROOT}/delete_service_${RUN_TIMESTAMP}.log"
FAILED_UUIDS_LOG="${LOG_ROOT}/delete_failed_uuids_${RUN_TIMESTAMP}.log"
SUCCESS_UUIDS_LOG="${LOG_ROOT}/delete_success_uuids_${RUN_TIMESTAMP}.log"
UUID_STATUS_LOG="${LOG_ROOT}/delete_uuid_status_${RUN_TIMESTAMP}.tsv"

log_message() {
  local message="$1"
  mkdir -p "${LOG_ROOT}"
  #echo "$(date -Is): ${message}" | tee -a "${ORCHESTRATOR_LOG}"
  echo "$(date -Is): ${message}"
  echo "$(date -Is): ${message}" >> "${ORCHESTRATOR_LOG}" 2>/dev/null || true
}

usage() {
  cat <<'EOF'
Usage: run_uuid_delete.sh --input-file <path> [options]

Options:
  --input-file <path>      Required. Text file containing one UUID per line.
  --batch-size <count>     Optional. UUIDs per request. Default: 1000.
  --env-file <path>        Optional. Shell env file to source before startup.
  --resume-run-dir <path>  Optional. Resume a prior run and skip completed batches.
  --jar-path <path>        Spring Boot jar to launch.
  --base-url <url>         Application base URL. Default: http://localhost:8080
  --health-path <path>     Health endpoint path. Default: /actuator/health
  --startup-timeout <sec>  Maximum wait for application startup. Default: 40
  --cleanup=false          Leave Spring Boot running after script exits
  --delete-path <path>     Delete endpoint path. Default: /delete
  --http-method <method>   HTTP method for the delete endpoint. Default: POST
  --request-format <type>  Request JSON: object => {"uuids":[]}; array => []. Default: object
  --continue-on-error      Log failed batches and continue.
  --skip-backup            Skip PAIC/FCLA before-state snapshots.
  --no-compress            Do not gzip/archive completed artifacts.
  --help                   Show this help text.

Examples:
  ./run_uuid_delete.sh --input-file ../data/uuids.txt
  ./run_uuid_delete.sh --input-file ../data/uuids.txt --delete-path /users/delete --batch-size 500
  ./run_uuid_delete.sh --input-file ../data/uuids.txt --http-method DELETE --request-format array
EOF
}

initialize_run_paths() {
  if [[ -n "${RESUME_RUN_DIR}" ]]; then
    RUN_DIR="${RESUME_RUN_DIR}"
  else
    RUN_DIR="${DATA_ROOT}/delete_run_${RUN_TIMESTAMP}"
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
  local existing=("${LOG_ROOT}"/*.log "${LOG_ROOT}"/*.tsv)
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
      log_message "Process ${pid} still alive after ${i}s; sending SIGKILL"
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
  local started_at
  local elapsed
  local health_url="${BASE_URL%/}${HEALTH_PATH}"

  started_at=$(date +%s)

  log_message "Waiting for application to be UP at ${health_url}"
  log_message "Startup timeout: ${STARTUP_TIMEOUT}s"

  while true; do

    local response
    response=$(curl -fsS "${health_url}" 2>/dev/null || true)

    if echo "${response}" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"'; then
      log_message "Application is UP"
      break
    fi

    elapsed=$(( $(date +%s) - started_at ))

    if (( elapsed >= STARTUP_TIMEOUT )); then
      log_message "ERROR: Application did not become healthy within ${STARTUP_TIMEOUT}s"
      exit 1
    fi

    attempts=$((attempts + 1))

    if (( attempts % 5 == 0 )); then
      log_message "Still waiting for application... elapsed=${elapsed}s"
    fi

    sleep 2
  done
}

start_app() {
  mkdir -p "${LOG_ROOT}" "${DATA_ROOT}"
  if [[ ! -f "${JAR_PATH}" ]]; then
    log_message "ERROR: Spring Boot jar not found: ${JAR_PATH}"
    exit 1
  fi
  local java_cmd="${JAVA_CMD:-java}"
  # shellcheck disable=SC2086
  nohup "${java_cmd}" ${JAVA_OPTS:-} -jar "${JAR_PATH}" >"${APP_LOG}" 2>&1 &
  APP_PID=$!
  echo "${APP_PID}" > "${PID_FILE}"
  log_message "Application launched with pid=${APP_PID}, log=${APP_LOG}"
  log_message "DEBUG: ABOUT TO CALL WAIT_FOR_APP"

  wait_for_app

  log_message "DEBUG: WAIT_FOR_APP RETURNED"
}

json_from_file() {
  local source_file="$1"
  local target_file="$2"
  tr -d '\0' < "${source_file}" \
    | sed '/^[[:space:]]*$/d; s/\r//g; s/\\/\\\\/g; s/"/\\"/g; s/.*/"&"/' \
    | paste -sd',' \
    | sed 's/^/[/; s/$/]/' > "${target_file}"
}

request_delete_body() {
  local uuid_json_file="$1"
  local target_file="$2"
  if [[ "${REQUEST_FORMAT}" == "array" ]]; then
    cp "${uuid_json_file}" "${target_file}"
  else
    printf '{"uuids":' > "${target_file}"
    cat "${uuid_json_file}" >> "${target_file}"
    printf '}' >> "${target_file}"
  fi
}

compress_batch_artifacts() {
  local dir="$1"
  [[ "${COMPRESS_ARTIFACTS}" == "true" ]] || return 0
  shopt -s nullglob
  local files=("${dir}"/*.json)
  shopt -u nullglob
  (( ${#files[@]} == 0 )) || gzip -f "${files[@]}" 2>/dev/null || true
}

rotate_app_log() {
  [[ "${COMPRESS_ARTIFACTS}" == "true" ]] || return 0
  [[ -f "${APP_LOG}" ]] || return 0
  local rotated="${APP_LOG}.$(date -u +%Y%m%dT%H%M%SZ).gz"
  cp "${APP_LOG}" "${rotated}.tmp" 2>/dev/null || return 0
  gzip -f "${rotated}.tmp" 2>/dev/null && mv "${rotated}.tmp.gz" "${rotated}" 2>/dev/null || true
  : > "${APP_LOG}"
  log_message "App log rotated to ${rotated}"
}

print_summary() {
  local run_elapsed=0
  if (( run_start_epoch > 0 )); then
    run_elapsed=$(( $(date +%s) - run_start_epoch ))
  fi
  log_message "========================================="
  log_message "RUN SUMMARY"
  log_message "  Total UUIDs submitted:   ${total_uuids_processed}"
  log_message "  UUIDs deleted:           ${total_uuids_deleted}"
  log_message "  UUIDs failed:            ${total_failed_uuids}"
  log_message "  Total batches:           $((total_batches_success + total_batches_failed))"
  log_message "  Batches successful:      ${total_batches_success}"
  log_message "  Batches failed:          ${total_batches_failed}"
  log_message "  Total elapsed:           ${run_elapsed}s"
  log_message "  Artifacts:               ${RUN_DIR:-<not set>}"
  log_message "  Delete log:              ${DELETE_LOG}"
  log_message "  Failed UUIDs log:        ${FAILED_UUIDS_LOG}"
  log_message "  Success UUIDs log:       ${SUCCESS_UUIDS_LOG}"
  log_message "  UUID status file:        ${UUID_STATUS_LOG}"
  log_message "========================================="
}

cleanup() {

  print_summary

  if [[ "${CLEANUP_APP}" == "true" ]]; then
    log_message "Cleanup enabled. Stopping application PID=${APP_PID}"
    stop_pid_if_running "${APP_PID:-}"
    rm -f "${PID_FILE}"
  else
    log_message "Cleanup disabled. Leaving application running."
  fi

  rm -f "${ORCHESTRATOR_PID_FILE}"
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
    [[ -d "${RUN_DIR}" ]] || { echo "Resume run directory not found: ${RUN_DIR}" >&2; exit 1; }
    [[ -d "${SPLIT_DIR}" ]] || { echo "Split directory not found: ${SPLIT_DIR}" >&2; exit 1; }
    touch "${COMPLETED_BATCHES_FILE}"
    log_message "Resuming prior run from ${RUN_DIR}"
    return
  fi
  [[ -f "${INPUT_FILE}" ]] || { echo "Input file not found: ${INPUT_FILE}" >&2; exit 1; }
  mkdir -p "${RUN_DIR}" "${SPLIT_DIR}"
  local clean_input="${RUN_DIR}/uuids.cleaned.txt"
  tr -d '\r\0' < "${INPUT_FILE}" | sed '/^[[:space:]]*$/d; s/^[[:space:]]*//; s/[[:space:]]*$//' > "${clean_input}"
  if [[ ! -s "${clean_input}" ]]; then
    echo "Input file contains no UUIDs after removing blank lines" >&2
    exit 1
  fi
  split -l "${BATCH_SIZE}" -d -a 8 "${clean_input}" "${SPLIT_DIR}/uuids_batch_"
  : > "${COMPLETED_BATCHES_FILE}"
  log_message "Input split into $(find "${SPLIT_DIR}" -maxdepth 1 -type f -name 'uuids_batch_*' | wc -l | tr -d ' ') batch file(s)"
}

write_batch_status() {
  local batch_file="$1"
  local status="$2"
  local status_log="$3"
  while IFS= read -r uuid; do
    [[ -z "${uuid}" ]] && continue
    printf '%s\t%s\n' "${uuid}" "${status}" >> "${status_log}"
  done < "${batch_file}"
}

ORIGINAL_ARGS=("$@")
while [[ $# -gt 0 ]]; do
  case "$1" in
    --input-file) INPUT_FILE="$2"; shift 2 ;;
    --env-file) ENV_FILE="$2"; shift 2 ;;
    --resume-run-dir) RESUME_RUN_DIR="$2"; shift 2 ;;
    --jar-path) JAR_PATH="$2"; shift 2 ;;
    --batch-size) BATCH_SIZE="$2"; shift 2 ;;
    --base-url) BASE_URL="$2"; shift 2 ;;
    --health-path) HEALTH_PATH="$2"; shift 2 ;;
    --delete-path) DELETE_PATH="$2"; shift 2 ;;
    --http-method) HTTP_METHOD="${2^^}"; shift 2 ;;
    --request-format) REQUEST_FORMAT="${2,,}"; shift 2 ;;
    --continue-on-error) CONTINUE_ON_ERROR=true; shift ;;
    --skip-backup) SKIP_BACKUP=true; shift ;;
    --no-compress) COMPRESS_ARTIFACTS=false; shift ;;
    --startup-timeout) STARTUP_TIMEOUT="$2"; shift 2;;
    --cleanup=false) CLEANUP_APP=false; shift;;
    --help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

initialize_run_paths
[[ -n "${INPUT_FILE}" || -n "${RESUME_RUN_DIR}" ]] || { usage; exit 1; }
[[ "${BATCH_SIZE}" =~ ^[0-9]+$ ]] && (( BATCH_SIZE >= 1 )) || { echo "batch-size must be a positive integer" >&2; exit 1; }
[[ "${REQUEST_FORMAT}" == "object" || "${REQUEST_FORMAT}" == "array" ]] || { echo "request-format must be object or array" >&2; exit 1; }
[[ "${DELETE_PATH}" == /* ]] || DELETE_PATH="/${DELETE_PATH}"

if [[ -n "${ENV_FILE}" ]]; then
  load_env_file "${ENV_FILE}"
elif [[ -f "${APP_HOME}/data/data_processor.env" ]]; then
  ENV_FILE="${APP_HOME}/data/data_processor.env"
  load_env_file "${ENV_FILE}"
fi

# Preserve backup support. These variables are required only when backups are enabled.
if [[ "${SKIP_BACKUP}" != "true" ]]; then
  require_env FCLA_DATABASE_URL
  require_env FCLA_DATABASE_APP_USER
  require_env FCLA_DATABASE_APP_PASSWORD
  require_env PAIC_HOSTNAME
  require_env PAIC_JWK_PATH
  require_env PAIC_SVC_ACCT_ID
fi

if [[ -t 0 ]]; then
  mkdir -p "${LOG_ROOT}"
  echo "Detaching to background. Monitor with:"
  echo "  tail -f ${ORCHESTRATOR_LOG}"
  echo "Kill with:"
  echo "  ${SCRIPT_DIR}/kill_data_processor.sh"
  nohup "$0" "${ORIGINAL_ARGS[@]}" < /dev/null > "${ORCHESTRATOR_LOG}" 2>&1 &
  echo "Background PID: $!"
  exit 0
fi

mkdir -p "${RUN_DIR}" "${LOG_ROOT}" "${DATA_ROOT}"
archive_logs
trap cleanup EXIT INT TERM
echo $$ > "${ORCHESTRATOR_PID_FILE}"
echo -e "uuid\tdelete_status" > "${UUID_STATUS_LOG}"

log_message "Starting bulk UUID deletion"
log_message "Input=${INPUT_FILE} base_url=${BASE_URL} delete_path=${DELETE_PATH} method=${HTTP_METHOD} request_format=${REQUEST_FORMAT} batch_size=${BATCH_SIZE} continue_on_error=${CONTINUE_ON_ERROR} skip_backup=${SKIP_BACKUP}"
[[ -z "${ENV_FILE}" ]] || log_message "Loaded environment from ${ENV_FILE}"

prepare_batch_inputs
stop_existing_app
log_message "DEBUG: before start_app"
start_app
log_message "DEBUG: after start_app"
run_start_epoch=$(date +%s)

log_message "DEBUG: BEFORE BATCH LOOP"
batch_number=0
for batch_file in "${SPLIT_DIR}"/uuids_batch_*; do
  [[ -f "${batch_file}" ]] || continue
  batch_name="$(basename "${batch_file}")"
  if is_completed_batch "${batch_name}"; then
    log_message "Skipping completed batch ${batch_name}"
    continue
  fi

  batch_number=$((batch_number + 1))
  batch_suffix="${batch_name#uuids_batch_}"
  batch_dir="${RUN_DIR}/batch_${batch_suffix}"
  mkdir -p "${batch_dir}"
  uuid_count=$(awk 'END{print NR}' "${batch_file}")
  total_uuids_processed=$((total_uuids_processed + uuid_count))
  batch_start_epoch=$(date +%s)
  log_message "=== BATCH ${batch_number} (${batch_name}) START: ${uuid_count} UUIDs ==="

  uuid_json="${batch_dir}/uuids.json"
  delete_body="${batch_dir}/delete_request.json"
  delete_result="${batch_dir}/delete_result.json"
  json_from_file "${batch_file}" "${uuid_json}"
  request_delete_body "${uuid_json}" "${delete_body}"

  if [[ "${SKIP_BACKUP}" != "true" ]]; then
    log_message "Batch ${batch_number}: Capturing PAIC and FCLA before-state"
    curl -fsS --max-time 600 -X POST -H 'Content-Type: application/json' --data-binary @"${uuid_json}" \
      "${BASE_URL%/}/paic/query" > "${batch_dir}/paic_before.json" &
    paic_pid=$!
    curl -fsS --max-time 120 -X POST -H 'Content-Type: application/json' --data-binary @"${uuid_json}" \
      "${BASE_URL%/}/fcladb/query" > "${batch_dir}/fcla_before.json" &
    fcla_pid=$!
    paic_ok=true; wait "${paic_pid}" || paic_ok=false
    fcla_ok=true; wait "${fcla_pid}" || fcla_ok=false
    echo "$(date -Is): Batch ${batch_number}: PAIC backup ${paic_ok}" >> "${PAIC_BACKUP_LOG}"
    echo "$(date -Is): Batch ${batch_number}: FCLA backup ${fcla_ok}" >> "${FCLA_BACKUP_LOG}"
    [[ "${paic_ok}" == "true" ]] || log_message "WARN: PAIC backup failed; delete will continue"
    [[ "${fcla_ok}" == "true" ]] || log_message "WARN: FCLA backup failed; delete will continue"
  else
    log_message "Batch ${batch_number}: Skipping before-state snapshots"
  fi

  delete_start=$(date +%s)
  log_message "Batch ${batch_number}: Calling ${HTTP_METHOD} ${BASE_URL%/}${DELETE_PATH}"
  curl_exit=0
  delete_http_code=$(curl -sS --max-time 600 -X "${HTTP_METHOD}" \
    -H 'Content-Type: application/json' \
    --data-binary @"${delete_body}" \
    -o "${delete_result}" \
    -w '%{http_code}' \
    "${BASE_URL%/}${DELETE_PATH}") || curl_exit=$?
  delete_elapsed=$(( $(date +%s) - delete_start ))
  batch_elapsed=$(( $(date +%s) - batch_start_epoch ))

  if (( curl_exit != 0 )); then
    delete_http_code="CURL_${curl_exit}"
  fi

  if [[ "${delete_http_code}" == 2?? ]]; then
    total_batches_success=$((total_batches_success + 1))
    total_uuids_deleted=$((total_uuids_deleted + uuid_count))
    write_batch_status "${batch_file}" "DELETED_HTTP_${delete_http_code}" "${UUID_STATUS_LOG}"
    write_batch_status "${batch_file}" "DELETED_HTTP_${delete_http_code}" "${SUCCESS_UUIDS_LOG}"
    echo "$(date -Is): Batch ${batch_number}: HTTP ${delete_http_code}; ${uuid_count} UUIDs submitted successfully in ${delete_elapsed}s -> ${delete_result}" >> "${DELETE_LOG}"
    log_message "Batch ${batch_number}: Delete completed with HTTP ${delete_http_code} in ${delete_elapsed}s"
    mark_batch_completed "${batch_name}"
  else
    total_batches_failed=$((total_batches_failed + 1))
    total_failed_uuids=$((total_failed_uuids + uuid_count))
    write_batch_status "${batch_file}" "DELETE_FAILED_HTTP_${delete_http_code}" "${UUID_STATUS_LOG}"
    write_batch_status "${batch_file}" "DELETE_FAILED_HTTP_${delete_http_code}" "${FAILED_UUIDS_LOG}"
    echo "$(date -Is): Batch ${batch_number}: FAILED HTTP ${delete_http_code} in ${delete_elapsed}s -> ${delete_result}" >> "${DELETE_LOG}"
    log_message "ERROR: Batch ${batch_number}: Delete failed with ${delete_http_code}; response=${delete_result}"
    if [[ "${CONTINUE_ON_ERROR}" != "true" ]]; then
      exit 1
    fi
    continue
  fi

  compress_batch_artifacts "${batch_dir}"
  log_message "=== BATCH ${batch_number} END: ${uuid_count} UUIDs in ${batch_elapsed}s ==="
  if (( batch_number % 50 == 0 )); then
    rotate_app_log
  fi
done

log_message "Bulk delete artifacts written to ${RUN_DIR}"
if [[ "${COMPRESS_ARTIFACTS}" == "true" && -d "${RUN_DIR}" ]]; then
  archive_file="${RUN_DIR}.tar.gz"
  log_message "Archiving run artifacts to ${archive_file}"
  if tar -czf "${archive_file}" -C "$(dirname "${RUN_DIR}")" "$(basename "${RUN_DIR}")" 2>/dev/null; then
    rm -rf "${RUN_DIR}"
    log_message "Run artifacts archived to ${archive_file}"
  else
    log_message "WARN: Archive failed; run directory preserved at ${RUN_DIR}"
  fi
fi
