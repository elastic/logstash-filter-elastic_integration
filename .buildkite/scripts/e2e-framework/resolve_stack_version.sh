#!/bin/bash

# This script resolves latest version from given N.x (where N is a precise, ex 8.x)
# Ensure you have set the ELASTIC_STACK_VERSION environment variable.

set -e

VERSION_URL="https://raw.githubusercontent.com/elastic/logstash/main/ci/logstash_releases.json"

if [ "$ELASTIC_STACK_VERSION" ]; then
    echo "Fetching versions from $VERSION_URL"
    VERSIONS=$(curl --silent $VERSION_URL)
    if [[ "$SNAPSHOT" = "true" ]]; then
      ELASTIC_STACK_RETRIEVED_VERSION=$(echo "$VERSIONS" | jq '.snapshots."'"$ELASTIC_STACK_VERSION"'"')
      echo $ELASTIC_STACK_RETRIEVED_VERSION
    else
      ELASTIC_STACK_RETRIEVED_VERSION=$(echo $VERSIONS | jq '.releases."'"$ELASTIC_STACK_VERSION"'"')
    fi
    if [[ "$ELASTIC_STACK_RETRIEVED_VERSION" != "null" ]]; then
      # remove starting and trailing double quotes
      ELASTIC_STACK_RETRIEVED_VERSION="${ELASTIC_STACK_RETRIEVED_VERSION%\"}"
      ELASTIC_STACK_RETRIEVED_VERSION="${ELASTIC_STACK_RETRIEVED_VERSION#\"}"
      echo "Translated $ELASTIC_STACK_VERSION to ${ELASTIC_STACK_RETRIEVED_VERSION}"
      export ELASTIC_STACK_VERSION=$ELASTIC_STACK_RETRIEVED_VERSION
    fi

    echo "Testing against version: $ELASTIC_STACK_VERSION"
else
    echo "Please set the ELASTIC_STACK_VERSION environment variable"
    echo "For example: export ELASTIC_STACK_VERSION=8.7.0"
    exit 1
fi
