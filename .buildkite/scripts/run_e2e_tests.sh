#!/bin/bash

set -euo pipefail

export PATH="/opt/buildkite-agent/.rbenv/bin:/opt/buildkite-agent/.pyenv/bin:$PATH"
eval "$(rbenv init -)"
eval "$(pyenv init -)"

###
# Checkout the target branch if defined
checkout_target_branch() {
  set +o nounset
  if [ -z "$TARGET_BRANCH" ]; then
    echo "Target branch is not specified, using default branch: main or BK defined"
  else
    echo "Changing the branch for ${TARGET_BRANCH}"
    git checkout "$TARGET_BRANCH"
  fi
}

###
# Set the Java home based on .java-version
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

checkout_target_branch
set_required_jdk
build_logstash
build_plugin

###
# Install E2E prerequisites and run E2E tests
python3 -mpip install -r .buildkite/scripts/e2e-pipeline/requirements.txt
python3 .buildkite/scripts/e2e-pipeline/main.py