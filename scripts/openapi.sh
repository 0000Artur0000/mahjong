#!/usr/bin/env sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
command=${1:-validate}

case "$command" in
  validate)
    docker run --rm -v "$root:/work" openapitools/openapi-generator-cli:v7.22.0 \
      validate -i /work/contracts/openapi/api.yaml
    ;;
  generate-frontend)
    output=${2:-frontend/src/generated/api}
    docker run --rm --user "$(id -u):$(id -g)" -v "$root:/work" \
      openapitools/openapi-generator-cli:v7.22.0 generate \
      -i /work/contracts/openapi/api.yaml -g typescript-fetch -o "/work/$output"
    ;;
  breaking)
    baseline=${2:?Usage: scripts/openapi.sh breaking path/to/baseline.yaml}
    docker run --rm -v "$root:/work" tufin/oasdiff:v1.17.0 \
      breaking "/work/$baseline" /work/contracts/openapi/api.yaml
    ;;
  *)
    echo "Usage: scripts/openapi.sh {validate|generate-frontend|breaking <baseline.yaml>}" >&2
    exit 2
    ;;
esac
