#!/usr/bin/env bash
set -euo pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly BASE_URL="https://kennytv.eu/entity-data"
readonly OUTPUT_DIR="${PROJECT_DIR}/src/main/resources/entity-data"
readonly TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TEMP_DIR}"' EXIT

curl --fail --silent --show-error --location \
  "${BASE_URL}/versions.json" --output "${TEMP_DIR}/versions.json"

jq -e 'type == "array" and all(.[]; type == "string" and length > 0)' \
  "${TEMP_DIR}/versions.json" >/dev/null

while IFS= read -r version; do
  if [[ ! "${version}" =~ ^[0-9A-Za-z._-]+$ ]]; then
    echo "Unsafe version in entity-data index: ${version}" >&2
    exit 1
  fi

  curl --fail --silent --show-error --location \
    "${BASE_URL}/${version}.json" --output "${TEMP_DIR}/${version}.json"

  jq -e '
    type == "object" and
    all(to_entries[];
      (.value | type == "object") and
      (.value | has("superClass")) and
      (.value.fields | type == "array") and
      all(.value.fields[];
        (.index | type == "number") and
        (.dataType | type == "string") and
        (.fieldName | type == "string")
      )
    )
  ' "${TEMP_DIR}/${version}.json" >/dev/null
done < <(jq -r '.[]' "${TEMP_DIR}/versions.json")

mkdir -p "${OUTPUT_DIR}"
find "${OUTPUT_DIR}" -maxdepth 1 -type f -name '*.json' -delete
install -m 0644 "${TEMP_DIR}"/*.json "${OUTPUT_DIR}/"

"${PROJECT_DIR}/gradlew" --quiet --project-dir "${PROJECT_DIR}" generateMetadataKeys

echo "Synced $(jq 'length' "${TEMP_DIR}/versions.json") entity-data versions."
