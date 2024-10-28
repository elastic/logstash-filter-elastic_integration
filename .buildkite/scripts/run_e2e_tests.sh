#!/bin/bash

set -euo pipefail

export PATH="/opt/buildkite-agent/.rbenv/bin:/opt/buildkite-agent/.pyenv/bin:/opt/buildkite-agent/.java/bin:$PATH"
export JAVA_HOME="/opt/buildkite-agent/.java"
eval "$(rbenv init -)"
eval "$(pyenv init -)"

VERSION_URL="https://raw.githubusercontent.com/elastic/logstash/main/ci/logstash_releases.json"

###
# Checkout the target branch if defined
checkout_target_branch() {
  if [ -z "$TARGET_BRANCH" ]; then
    echo "Target branch is not specified, using default branch: main or BK defined"
  else
    echo "Changing the branch for ${TARGET_BRANCH}"
    git checkout "$TARGET_BRANCH"
  fi
}

###
# Resolve stack version and export
resolve_current_stack_version() {
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

checkout_target_branch
build_logstash
build_plugin

###
# Install E2E prerequisites and run E2E tests
python3 -mpip install -r .buildkite/scripts/e2e/requirements.txt
python3 .buildkite/scripts/e2e/main.py