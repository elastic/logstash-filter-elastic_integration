#!/bin/bash

set -euo pipefail

export PATH="/opt/buildkite-agent/.rbenv/bin:/opt/buildkite-agent/.pyenv/bin:$PATH"
eval "$(rbenv init -)"
eval "$(pyenv init -)"

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
# Install a known-good Docker Compose version into ~/.docker/cli-plugins,
# which takes precedence over the (broken v2.24.7) binary baked into the
# agent image. Mirrors elastic/elastic-package's own CI setup in
# .buildkite/scripts/install_deps.sh (with_docker_compose_plugin).
install_docker_compose() {
  local docker_compose_version="${DOCKER_COMPOSE_VERSION:-v2.25.0}"

  local platform hw_type
  platform="$(uname | tr '[:upper:]' '[:lower:]')"
  hw_type="$(uname -m)"

  local docker_config="$HOME/.docker/cli-plugins"
  mkdir -p "$docker_config"

  echo "Installing Docker Compose ${docker_compose_version}"
  retry -t 5 -- curl -sSL -o "${docker_config}/docker-compose" "https://github.com/docker/compose/releases/download/${docker_compose_version}/docker-compose-${platform}-${hw_type}"
  chmod +x "${docker_config}/docker-compose"
  docker compose version
}

###
# Build the plugin, to do so we need Logstash source
build_logstash() {
  set +o nounset
  local branch="${TARGET_BRANCH:-main}"
  set -o nounset

  if [[ -d /usr/local/git-references/git-github-com-elastic-logstash-git ]]; then
      retry -t 5 -- git clone -v --reference /usr/local/git-references/git-github-com-elastic-logstash-git --branch "$branch" -- https://github.com/elastic/logstash.git .
  else
      retry -t 5 -- git clone --single-branch --branch "$branch" https://github.com/elastic/logstash.git
  fi

  cd logstash && ./gradlew clean bootstrap assemble installDefaultGems && cd ..
  LOGSTASH_PATH=$(pwd)/logstash
  export LOGSTASH_PATH
}

build_plugin() {
  ./gradlew clean vendor localGem
}

set_required_jdk
install_docker_compose
build_logstash
build_plugin

###
# Install E2E prerequisites and run E2E tests
python3 -mpip install -r .buildkite/scripts/e2e-pipeline/requirements.txt
python3 .buildkite/scripts/e2e-pipeline/main.py