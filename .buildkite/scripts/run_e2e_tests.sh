#!/bin/bash

set -euo pipefail

export PATH="/opt/buildkite-agent/.rbenv/bin:/opt/buildkite-agent/.pyenv/bin:/opt/buildkite-agent/.java/bin:$PATH"
export JAVA_HOME="/opt/buildkite-agent/.java"
eval "$(rbenv init -)"

VERSION_URL="https://storage.googleapis.com/artifacts-api/releases/current"

###
# Resolve stack version and export
resolve_current_stack_version() {
  set +o nounset

  local major_version="${ELASTIC_STACK_VERSION%%.*}"
  local version=$(curl --retry 5 --retry-delay 5 -fsSL "$VERSION_URL/$major_version")

  echo "Resolved version: $version"
  export STACK_VERSION="$version"
}

resolve_current_stack_version

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

build_logstash
build_plugin

###
# TODO: disable ls command and enable with Python E2E scripts
ls -a
# Install prerequisites and run E2E tests
#pip install -r .buildkite/scripts/e2e/requirements.txt
#python3 .buildkite/scripts/e2e/main.py