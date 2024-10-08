#!/bin/bash

set -euo pipefail

set +o nounset
if [[ "$SNAPSHOT" == "true" ]] && [[ "$ELASTIC_STACK_VERSION" == "8.x" ]]; then
  export ELASTICSEARCH_TREEISH=8.x
else
  VERSION_URL="https://raw.githubusercontent.com/elastic/logstash/main/ci/logstash_releases.json"

  echo "Fetching versions from $VERSION_URL"
  VERSIONS=$(curl --retry 5 --retry-delay 5 -fsSL $VERSION_URL)

  if [[ "$SNAPSHOT" == "true" ]]; then
    key=$(echo "$VERSIONS" | jq -r '.snapshots."'"$ELASTIC_STACK_VERSION"'"')
  else
    key=$(echo "$VERSIONS" | jq -r '.releases."'"$ELASTIC_STACK_VERSION"'"')
  fi

  export ELASTICSEARCH_TREEISH=${key%.*}
fi
