#!/usr/bin/env bash
set -Eeuo pipefail

: "${REMOTE_APP_DIR:?REMOTE_APP_DIR is required}"
: "${DEPLOY_BRANCH:?DEPLOY_BRANCH is required}"
: "${DEPLOY_SHA:?DEPLOY_SHA is required}"

PUBLIC_HOST="${PUBLIC_HOST:-crowdcam.co.za}"
VERIFY_URL="${VERIFY_URL:-https://${PUBLIC_HOST}/}"
COMPOSE_OVERRIDE_FILE="${COMPOSE_OVERRIDE_FILE:-}"
DOPPLER_ENABLED="${DOPPLER_ENABLED:-true}"
DOPPLER_PROJECT="${DOPPLER_PROJECT:-onlinepos}"
DOPPLER_CONFIG="${DOPPLER_CONFIG:-prd}"
KEEP_ROLLBACK_IMAGE="${KEEP_ROLLBACK_IMAGE:-false}"
RUNTIME_ENV_CREATED="${RUNTIME_ENV_CREATED:-false}"
REMOTE_ENV="${REMOTE_ENV:-}"
ROLLBACK_TAG="onlinepossystem-app:rollback"
OLD_CONTAINER_ID=""
OLD_IMAGE_ID=""
ROLLBACK_AVAILABLE=false
APP_RECREATED=false

cleanup() {
  case "$0" in
    /tmp/onlinepos-deploy-*.sh) rm -f -- "$0" ;;
  esac
}
trap cleanup EXIT

compose() {
  local compose_files=(-f docker/docker-compose.yml)
  if [[ -n "$COMPOSE_OVERRIDE_FILE" ]]; then
    compose_files+=(-f "$COMPOSE_OVERRIDE_FILE")
  fi
  docker compose --env-file SupportConfigFiles/.env \
    "${compose_files[@]}" -p onlinepossystem "$@"
}

verify_app() {
  curl --fail --silent --show-error --max-time 10 "$VERIFY_URL" >/dev/null
}

refresh_doppler_env() {
  command -v doppler >/dev/null 2>&1 || {
    echo "doppler is not installed on the server; install it and configure a read-only production service token" >&2
    exit 1
  }

  mkdir -p SupportConfigFiles
  local tmp_env
  tmp_env="$(mktemp SupportConfigFiles/.env.doppler.XXXXXX)"
  chmod 600 "$tmp_env"

  local doppler_args=(secrets download --no-file --format docker)
  if [[ -n "$DOPPLER_PROJECT" ]]; then
    doppler_args+=(--project "$DOPPLER_PROJECT")
  fi
  if [[ -n "$DOPPLER_CONFIG" ]]; then
    doppler_args+=(--config "$DOPPLER_CONFIG")
  fi

  if ! doppler "${doppler_args[@]}" > "$tmp_env"; then
    rm -f "$tmp_env"
    echo "Failed to render SupportConfigFiles/.env from Doppler" >&2
    exit 1
  fi

  mv "$tmp_env" SupportConfigFiles/.env
  chmod 600 SupportConfigFiles/.env
}

rollback() {
  local status=$?
  trap - ERR
  set +e
  echo "Deployment failed; restoring the previous application container." >&2

  if [[ "$APP_RECREATED" == "true" && "$ROLLBACK_AVAILABLE" == "true" ]]; then
    docker tag "$ROLLBACK_TAG" onlinepossystem-app:latest
    compose up -d --no-deps --force-recreate app
  elif [[ -n "$OLD_CONTAINER_ID" ]]; then
    docker start "$OLD_CONTAINER_ID" >/dev/null
  fi

  compose ps >&2
  compose logs --tail=160 app >&2
  exit "$status"
}

test -d "$REMOTE_APP_DIR/.git" || { echo "Remote app directory is not a git repository: $REMOTE_APP_DIR" >&2; exit 1; }
command -v git >/dev/null 2>&1 || { echo "git is not installed on the server" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "docker is not installed on the server" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "curl is not installed on the server" >&2; exit 1; }
command -v flock >/dev/null 2>&1 || { echo "flock is not installed on the server" >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 is not installed on the server" >&2; exit 1; }
if [[ "$DOPPLER_ENABLED" == "true" ]]; then
  command -v doppler >/dev/null 2>&1 || { echo "doppler is not installed on the server" >&2; exit 1; }
fi

cd "$REMOTE_APP_DIR"
exec 9>.git/online-pos-deploy.lock
if ! flock -w 1200 9; then
  echo "Timed out waiting for another production deployment to finish" >&2
  exit 1
fi

git fetch origin "$DEPLOY_BRANCH"
git cat-file -e "${DEPLOY_SHA}^{commit}" || {
  echo "Deployment revision is not present in the production repository: $DEPLOY_SHA" >&2
  exit 1
}
if ! git merge-base --is-ancestor "$DEPLOY_SHA" "origin/$DEPLOY_BRANCH"; then
  echo "Refusing to deploy $DEPLOY_SHA because it is not on origin/$DEPLOY_BRANCH" >&2
  exit 1
fi
git checkout --force --detach "$DEPLOY_SHA"

ACTUAL_SHA="$(git rev-parse HEAD)"
if [[ "$ACTUAL_SHA" != "$DEPLOY_SHA" ]]; then
  echo "Refusing to deploy $ACTUAL_SHA; workflow tested $DEPLOY_SHA" >&2
  exit 1
fi

mkdir -p SupportConfigFiles
if [[ "$DOPPLER_ENABLED" == "true" ]]; then
  refresh_doppler_env
elif [[ "$RUNTIME_ENV_CREATED" == "true" ]]; then
  test -n "$REMOTE_ENV" || { echo "REMOTE_ENV is required when using uploaded runtime env files" >&2; exit 1; }
  test -f "$REMOTE_ENV" || { echo "Uploaded environment file missing: $REMOTE_ENV" >&2; exit 1; }
  mv "$REMOTE_ENV" SupportConfigFiles/.env
fi

test -f SupportConfigFiles/.env || { echo "SupportConfigFiles/.env is missing on the server" >&2; exit 1; }
chmod 600 SupportConfigFiles/.env
export GIT_SHA="$DEPLOY_SHA"

GOOGLE_MAPS_API_KEY="$(
  compose config --format json \
    | python3 -c 'import json, sys; print(json.load(sys.stdin)["services"]["app"]["environment"].get("GOOGLE_MAPS_API_KEY", ""), end="")'
)"
test -n "$GOOGLE_MAPS_API_KEY" || { echo "GOOGLE_MAPS_API_KEY is missing from the rendered production environment" >&2; exit 1; }
printf '%s' "$GOOGLE_MAPS_API_KEY" \
  | python3 scripts/google-places-preflight.py "https://${PUBLIC_HOST}"
unset GOOGLE_MAPS_API_KEY

compose up -d db
OLD_CONTAINER_ID="$(compose ps -aq app 2>/dev/null || true)"
if [[ -n "$OLD_CONTAINER_ID" ]]; then
  if [[ "$(docker inspect --format '{{.State.Running}}' "$OLD_CONTAINER_ID")" != "true" ]]; then
    echo "Restarting the previous application container before deployment."
    docker start "$OLD_CONTAINER_ID" >/dev/null
  fi
  OLD_IMAGE_ID="$(docker inspect --format '{{.Image}}' "$OLD_CONTAINER_ID")"
  if docker image inspect "$OLD_IMAGE_ID" >/dev/null 2>&1; then
    docker tag "$OLD_IMAGE_ID" "$ROLLBACK_TAG"
  elif docker image inspect "$ROLLBACK_TAG" >/dev/null 2>&1; then
    echo "Using the existing rollback image because the running container image metadata is unavailable."
  else
    docker commit --pause=false "$OLD_CONTAINER_ID" "$ROLLBACK_TAG" >/dev/null
  fi
  ROLLBACK_AVAILABLE=true
fi

compose build app migrate

trap rollback ERR
# These commands must never inherit the SSH command stream as standard input.
compose run --rm --interactive=false provision </dev/null
compose run --rm --interactive=false migrate </dev/null
APP_RECREATED=true
compose up -d --no-deps --force-recreate app

APP_CONTAINER_ID="$(compose ps -q app)"
test -n "$APP_CONTAINER_ID"

for _ in $(seq 1 60); do
  HEALTH_STATUS="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$APP_CONTAINER_ID")"
  if [[ "$HEALTH_STATUS" == "healthy" ]] && verify_app; then
    break
  fi
  sleep 2
done

HEALTH_STATUS="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$APP_CONTAINER_ID")"
[[ "$HEALTH_STATUS" == "healthy" ]] || { echo "Application container is not healthy: $HEALTH_STATUS" >&2; false; }

IMAGE_REVISION="$(docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$APP_CONTAINER_ID")"
[[ "$IMAGE_REVISION" == "$DEPLOY_SHA" ]] || {
  echo "Running image revision $IMAGE_REVISION does not match tested revision $DEPLOY_SHA" >&2
  false
}

verify_app

trap - ERR
if [[ "$ROLLBACK_AVAILABLE" == "true" && "$KEEP_ROLLBACK_IMAGE" != "true" ]]; then
  docker image rm "$ROLLBACK_TAG" >/dev/null 2>&1 || true
fi
compose ps
echo "Deployed and verified revision $DEPLOY_SHA"
if [[ "$ROLLBACK_AVAILABLE" == "true" && "$KEEP_ROLLBACK_IMAGE" == "true" ]]; then
  echo "Retained the previous application image until production smoke verification completes"
fi
