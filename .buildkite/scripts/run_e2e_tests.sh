#!/bin/bash

set -euo pipefail

export PATH="/opt/buildkite-agent/.rbenv/bin:/opt/buildkite-agent/.pyenv/bin:$PATH"
eval "$(rbenv init -)"
eval "$(pyenv init -)"

VERSION_URL="https://raw.githubusercontent.com/elastic/logstash/main/ci/logstash_releases.json"

###
# Resolve stack version and export
resolve_current_stack_version() {
  set +o nounset
  echo "Fetching versions from $VERSION_URL"
  VERSIONS=$(curl --retry 5 --retry-delay 5 -fsSL $VERSION_URL)

  if [[ "$SNAPSHOT" == "true" ]]; then
    key=$(echo "$VERSIONS" | jq -r '.snapshots."'"$ELASTIC_STACK_VERSION"'"')
    echo "resolved key: $key"
  else
    key=$(echo "$VERSIONS" | jq -r '.releases."'"$ELASTIC_STACK_VERSION"'"')
  fi

  echo "Resolved version: $key"
  export STACK_VERSION="$key"
}

set_required_jdk() {
  set +o nounset
  java_version="$(cat .java-version)"
  echo "Required JDK version: $java_version"
  if [[ "$java_version" == "17.0" ]]; then
    jdk_home="/opt/buildkite-agent/.java/adoptiumjdk_17"
  elif [[ "$java_version" == "21.0" ]]; then
    jdk_home="/opt/buildkite-agent/.java/adoptiumjdk_21"
  else
    echo "Unsupported JDK."
    exit 1
  fi

  export JAVA_HOME=$jdk_home
  export PATH="$jdk_home:$PATH"
}

###
# Build the plugin, to do so we need Logstash source
build_logstash() {
  if [[ -d /usr/local/git-references/git-github-com-elastic-logstash-git ]]; then
      retry -t 5 -- git clone -v --reference /usr/local/git-references/git-github-com-elastic-logstash-git -- https://github.com/elastic/logstash.git .
  else
      retry -t 5 -- git clone --single-branch https://github.com/elastic/logstash.git
  fi

  cd logstash && ./gradlew clean bootstrap assemble installDefaultGems && cd ..
  LOGSTASH_PATH=$(pwd)/logstash
  export LOGSTASH_PATH
}

build_plugin() {
  ./gradlew clean vendor localGem
}

resolve_current_stack_version
set_required_jdk
build_logstash
build_plugin

###
# Run E2E tests
python3 .buildkite/scripts/e2e-pipeline/main.py