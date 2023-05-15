#!/bin/bash

set -e

# resolve latest elastic stack version from n.x (ex, 8.x). Resolved version would be 8.7.0
source ./e2e-framework/resolve_stack_version.sh

# download latest released elastic-package
./e2e-framework/download_elastic_package.sh $1 # the platform can be either macos or debian

# Pull docker files
pull_docker_file_of() {
  project="${1?project name required}"
  local docker_image="docker.elastic.co/${project}/${project}:${ELASTIC_STACK_VERSION}"
  echo "Pulling $docker_image"
  docker pull "$docker_image"
}

################### Built plugin ##############################
./e2e-framework/configs/build-plugin.sh

###############################################################

################### Elastic stack #############################
# run ES, KB and ERP
./elastic-package stack up –version "${ELASTIC_STACK_VERSION}" --services package-registry,kibana &

sleep 10 # let ES & Kibana spin up

# copy certs from elasticsearch-1 container, this is required when connecting to ES from LS
rm -rf tmp
mkdir tmp
docker cp elastic-package-stack-elasticsearch-1:/usr/share/elasticsearch/config/certs tmp
###############################################################

################### Logstash ##################################
# todo: use docker composer to run Logstash
###############################################################

source e2e-framework/configs/stacks.conf
if [[ $elastic_agent == true ]]; then
  pull_docker_file_of "elastic-agent"
  ./e2e-framework/configs/stacks/elastic-agent/elastic-agent.sh
  sleep 5
fi
# TODO: create a script (similar to elastic-agent.sh) under stacks folder to setup and run the docker file