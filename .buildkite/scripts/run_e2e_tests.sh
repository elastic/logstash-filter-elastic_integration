#!/bin/bash

set -euo pipefail

export PATH="/opt/buildkite-agent/.rbenv/bin:/opt/buildkite-agent/.pyenv/bin:$PATH"
eval "$(rbenv init -)"
eval "$(pyenv init -)"

# Build elastic-package from this repo/ref instead of using the latest release.
# Unset ELASTIC_PACKAGE_REF to go back to the released binary.
export ELASTIC_PACKAGE_REPO="${ELASTIC_PACKAGE_REPO:-https://github.com/mrodm/elastic-package.git}"
export ELASTIC_PACKAGE_REF="${ELASTIC_PACKAGE_REF:-show_error_container_setup}"

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
# Install Go with gvm when elastic-package needs to be built from source
# (ELASTIC_PACKAGE_REF set). Mirrors elastic/elastic-package's own CI setup
# in .buildkite/scripts/install_deps.sh (with_go).
install_go() {
  set +o nounset
  if [ -z "$ELASTIC_PACKAGE_REF" ]; then
    echo "ELASTIC_PACKAGE_REF is not set, skipping Go installation (elastic-package release binary will be used)"
    return
  fi

  local setup_gvm_version="${SETUP_GVM_VERSION:-v0.6.0}"
  local bin_dir
  bin_dir="$(pwd)/bin"
  mkdir -p "$bin_dir"
  export PATH="${bin_dir}:${PATH}"

  local platform arch
  platform="$(uname | tr '[:upper:]' '[:lower:]')"
  arch="$(uname -m)"
  case "$arch" in
    x86_64) arch="amd64" ;;
    aarch64|arm64) arch="arm64" ;;
    *) echo "Unsupported architecture: $arch"; exit 1 ;;
  esac

  retry -t 5 -- curl -sSL -o "${bin_dir}/gvm" "https://github.com/andrewkroh/gvm/releases/download/${setup_gvm_version}/gvm-${platform}-${arch}"
  chmod +x "${bin_dir}/gvm"

  # Use the Go version pinned by the elastic-package ref being built,
  # falling back to main's .go-version. GO_VERSION overrides both.
  local repo_path
  repo_path="${ELASTIC_PACKAGE_REPO:-https://github.com/elastic/elastic-package.git}"
  repo_path="${repo_path#https://github.com/}"
  repo_path="${repo_path%.git}"
  local go_version="$GO_VERSION"
  if [ -z "$go_version" ]; then
    go_version="$(curl -fsSL "https://raw.githubusercontent.com/${repo_path}/${ELASTIC_PACKAGE_REF}/.go-version" || true)"
  fi
  if [ -z "$go_version" ]; then
    go_version="$(curl -fsSL "https://raw.githubusercontent.com/elastic/elastic-package/main/.go-version")"
  fi

  echo "Installing Go ${go_version} with gvm ${setup_gvm_version}"
  eval "$(gvm "$go_version")"
  go version
  PATH="${PATH}:$(go env GOPATH)/bin"
  export PATH
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

checkout_target_branch
set_required_jdk
install_go
build_logstash
build_plugin

echo "Show docker compose version"
docker version
docker-compose version
docker compose version
###
# Install E2E prerequisites and run E2E tests
python3 -mpip install -r .buildkite/scripts/e2e-pipeline/requirements.txt
python3 .buildkite/scripts/e2e-pipeline/main.py
