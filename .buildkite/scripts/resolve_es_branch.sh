#!/bin/bash

set -e

VERSION_URL="https://raw.githubusercontent.com/elastic/logstash/main/ci/logstash_releases.json"

echo "Fetching versions from $VERSION_URL"
VERSIONS=$(curl --silent $VERSION_URL)

if [[ "$SNAPSHOT" == "true" ]]; then
  key=$(echo "$VERSIONS" | jq '.snapshots."'"$ELASTIC_STACK_VERSION"'"')
else
  key=$(echo "$VERSIONS" | jq '.releases."'"$ELASTIC_STACK_VERSION"'"')
fi

# remove the wrapped quotation marks ("")
resolved_stack_version=$(echo "$key" | sed 's/\"//g')

IFS='.'
read -a versions <<< "$resolved_stack_version"
target_branch=${versions[0]}.${versions[1]}

echo "Using $target_branch branch."
export ELASTICSEARCH_TREEISH=$target_branch
