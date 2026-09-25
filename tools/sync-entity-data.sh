#!/usr/bin/env bash
set -euo pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly BASE_URL="https://kennytv.eu/entity-data"
readonly OUTPUT_DIR="${PROJECT_DIR}/src/main/resources/entity-data"
readonly TEMP_DIR="$(mktemp -d)"
readonly MERGED_DIR="${TEMP_DIR}/merged"
readonly PREVIOUS_DIR="${TEMP_DIR}/previous"
trap 'rm -rf "${TEMP_DIR}"' EXIT

# Keep the currently bundled snapshots so that reviewed snapshots which upstream has
# not published yet survive the destructive reinstall below instead of being dropped.
if [[ -d "${OUTPUT_DIR}" ]]; then
  mkdir -p "${PREVIOUS_DIR}"
  find "${OUTPUT_DIR}" -maxdepth 1 -type f -name '*.json' -exec cp -t "${PREVIOUS_DIR}" {} +
fi

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

node "${PROJECT_DIR}/tools/merge-entity-data.mjs" "${TEMP_DIR}" "${MERGED_DIR}"

mkdir -p "${OUTPUT_DIR}"
find "${OUTPUT_DIR}" -maxdepth 1 -type f -name '*.json' -delete
install -m 0644 "${MERGED_DIR}"/*.json "${OUTPUT_DIR}/"

preserved=()
if [[ -f "${PREVIOUS_DIR}/versions.json" ]]; then
  while IFS= read -r version; do
    # The freshly installed index already contains every upstream snapshot plus the
    # legacy merged ones, so only snapshots missing from it need to be restored.
    if jq -e --arg version "${version}" 'index($version) != null' \
      "${OUTPUT_DIR}/versions.json" >/dev/null; then
      continue
    fi
    if [[ ! -f "${PREVIOUS_DIR}/${version}.json" ]]; then
      echo "Bundled snapshot ${version} is not published upstream and was not backed up; dropping it." >&2
      continue
    fi

    install -m 0644 "${PREVIOUS_DIR}/${version}.json" "${OUTPUT_DIR}/${version}.json"
    jq --arg version "${version}" '. + [$version]' \
      "${OUTPUT_DIR}/versions.json" > "${OUTPUT_DIR}/versions.json.tmp"
    mv "${OUTPUT_DIR}/versions.json.tmp" "${OUTPUT_DIR}/versions.json"
    preserved+=("${version}")
  done < <(jq -r '.[]' "${PREVIOUS_DIR}/versions.json")
fi

"${PROJECT_DIR}/gradlew" --quiet --project-dir "${PROJECT_DIR}" generateMetadataKeys
node "${PROJECT_DIR}/tools/verify-entity-data.mjs" \
  "${OUTPUT_DIR}" \
  "${PROJECT_DIR}/data/metadata-flags/semantic-flags.json"

echo "Synced and merged $(jq 'length' "${OUTPUT_DIR}/versions.json") entity-data versions."
if [[ "${#preserved[@]}" -gt 0 ]]; then
  echo "Preserved locally reviewed snapshots that upstream does not publish yet: ${preserved[*]}"
fi
