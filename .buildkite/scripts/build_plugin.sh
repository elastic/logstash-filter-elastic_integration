#!/bin/bash

set -e

TARGET_VERSIONS=()

install_java() {
  sudo apt update && sudo apt install -y openjdk-17-jdk && sudo apt install -y openjdk-17-jre
}

clone_plugin_repo() {
  echo "Cloning logstash-filter-elastic_integration repo..."
  git clone https://github.com/elastic/logstash-filter-elastic_integration.git
}

resolve_latest_versions() {
  source resolve_stack_version.sh
  for resolved_version in "${ELASTIC_STACK_VERSIONS[@]}"
  do
    IFS='.'
    read -a versions <<< "$resolved_version"
    version=${versions[0]}.${versions[1]}
    TARGET_VERSIONS+=("$version")
  done

  if [[ "$SNAPSHOT" == "true" ]]; then
     TARGET_VERSIONS+=("main")
  fi
}

build_plugin() {
  cd logstash-filter-elastic_integration
  ./gradlew clean build generateGemJarRequiresFile && cd ..
}

install_java
clone_plugin_repo
resolve_latest_versions

for target_version in "${TARGET_VERSIONS[@]}"
do
  echo "Using $target_version version."
  export ELASTICSEARCH_TREEISH=$target_version
  build_plugin
done
