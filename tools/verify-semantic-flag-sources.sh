#!/usr/bin/env bash
set -euo pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SOURCES="${PROJECT_DIR}/data/metadata-flags/semantic-flags.json"
readonly TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TEMP_DIR}"' EXIT

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

count=0
while IFS=$'\t' read -r source_id release expected_server url expected_mappings; do
  package_file="${TEMP_DIR}/${source_id}.json"
  curl --fail --silent --show-error --location "${url}" --output "${package_file}"

  actual_release="$(jq -er '.id' "${package_file}")"
  actual_server="$(jq -er '.downloads.server.sha1' "${package_file}")"
  if [[ "${actual_release}" != "${release}" ]]; then
    echo "Release mismatch for ${source_id}: expected ${release}, got ${actual_release}" >&2
    exit 1
  fi
  if [[ "${actual_server}" != "${expected_server}" ]]; then
    echo "Server SHA-1 mismatch for ${source_id}" >&2
    exit 1
  fi
  if [[ -n "${expected_mappings}" ]]; then
    actual_mappings="$(jq -er '.downloads.server_mappings.sha1' "${package_file}")"
    if [[ "${actual_mappings}" != "${expected_mappings}" ]]; then
      echo "Server mappings SHA-1 mismatch for ${source_id}" >&2
      exit 1
    fi
  fi
  count=$((count + 1))
done < <(
  jq -r '.sources[]
    | select(.url | startswith("https://piston-meta.mojang.com/"))
    | [.id, .release, (.serverSha1 // .sha1), .url, (.mappingsSha1 // "")]
    | @tsv' "${SOURCES}"
)

echo "Verified ${count} pinned Mojang semantic-flag source records."
