#!/usr/bin/env bash
set -euo pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SOURCES="${PROJECT_DIR}/data/legacy-entity-data/sources.json"
readonly TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TEMP_DIR}"' EXIT

curl --fail --silent --show-error --location \
  'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json' \
  --output "${TEMP_DIR}/versions.json"

while IFS=$'\t' read -r snapshot release expected_server_sha1 expected_build_data; do
  version_url="$(jq -er --arg release "${release}" \
    '.versions[] | select(.id == $release) | .url' "${TEMP_DIR}/versions.json")"
  actual_server_sha1="$(curl --fail --silent --show-error --location "${version_url}" \
    | jq -er '.downloads.server.sha1')"
  actual_build_data="$(curl --fail --silent --show-error --location \
    "https://hub.spigotmc.org/versions/${release}.json" | jq -er '.refs.BuildData')"

  if [[ "${actual_server_sha1}" != "${expected_server_sha1}" ]]; then
    echo "Mojang server SHA-1 mismatch for ${snapshot} (${release})" >&2
    exit 1
  fi
  if [[ "${actual_build_data}" != "${expected_build_data}" ]]; then
    echo "Spigot BuildData commit mismatch for ${snapshot} (${release})" >&2
    exit 1
  fi
done < <(jq -r '.[] | [.snapshot, .verificationRelease, .serverSha1, .buildDataCommit] | @tsv' "${SOURCES}")

echo "Verified $(jq 'length' "${SOURCES}") legacy entity-data source records."
