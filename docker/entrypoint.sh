#!/usr/bin/env bash
# Container entrypoint. Sanity-checks the Android SDK wiring before handing off
# to the requested command (shell, gradle, claude, etc.).
set -euo pipefail

PROJECT_DIR="${PROJECT_DIR:-/home/lawrenceley/src/mobile-agent}"
LOCAL_PROPS="${PROJECT_DIR}/android-app/local.properties"

# Self-heal cache-volume ownership. Docker creates named-volume mountpoints with
# the daemon's UID (root) if the in-image path didn't exist, and once a volume
# exists the Dockerfile chown can't reach it. Cheap idempotent fix on each start.
for dir in "${HOME}/.gradle" "${HOME}/.android" "${HOME}/.npm"; do
  if [[ -d "${dir}" && "$(stat -c %u "${dir}")" != "$(id -u)" ]]; then
    sudo chown -R "$(id -u):$(id -g)" "${dir}"
  fi
done

# Warn (don't rewrite) if local.properties pins sdk.dir to a host path. AGP reads
# local.properties before ANDROID_HOME, so a stale entry will silently break the
# build. Fix is one-time on the host: delete the sdk.dir line and let env take over.
if [[ -f "${LOCAL_PROPS}" ]]; then
  host_sdk="$(grep -E '^sdk\.dir=' "${LOCAL_PROPS}" | head -n1 | cut -d= -f2- || true)"
  if [[ -n "${host_sdk}" && "${host_sdk}" != "${ANDROID_HOME}" ]]; then
    cat >&2 <<EOF
[entrypoint] WARNING: ${LOCAL_PROPS}
  has sdk.dir=${host_sdk}
  which won't exist in this container (ANDROID_HOME=${ANDROID_HOME}).
  Remove that line on the host; Gradle will fall back to ANDROID_HOME on both sides.
EOF
  fi
fi

exec "$@"
