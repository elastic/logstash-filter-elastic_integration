#!/bin/bash

set -e

if [ "$(command -v apt-get)" ]; then \
  apt-get update -y --fix-missing && \
  apt-get install -y shared-mime-info; \
  sudo apt-get install ruby ruby-dev
  sudo gem install bundler
else \
  echo "Try to use environment bundler."
fi

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

pull_docker_file_of "logstash"

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
# create a logstash-container docker container
docker create --name logstash-container --network elastic-package-stack_default \
  -h logstash-host \
  -p 9600:9600 -p 5044:5044/tcp \
  docker.elastic.co/logstash/logstash:"${ELASTIC_STACK_VERSION}"

# copy ES certificates
docker cp tmp/certs logstash-container:/usr/share/logstash/config

# run logstash-container, copy locally built plugin to container and install plugin
docker start logstash-container &
sleep 10
docker exec -it logstash-container sh -c "mkdir plugins"
cd .. && cd .. && cd ..
docker cp logstash-filter-elastic_integration logstash-container:/usr/share/logstash/plugins/
cd logstash-filter-elastic_integration/.buildkite/scripts

# Replace if plugin is embedded, otherwise append at tail
docker exec -it logstash-container sh -c "echo 'gem \"logstash-filter-elastic_integration\", :path=>\"/usr/share/logstash/plugins/logstash-filter-elastic_integration\"' >> /usr/share/logstash/Gemfile"

# Install the plugin
docker exec -it logstash-container sh -c "bin/logstash-plugin install --no-verify"

# copy config files to container and rerun logstash-container
docker cp e2e-framework/configs/logstash.conf logstash-container:/usr/share/logstash/pipeline
docker cp e2e-framework/configs/logstash.yml logstash-container:/usr/share/logstash/config

# restarting Logstash after installing the plugin
docker stop logstash-container
sleep 5
docker start logstash-container
sleep 5
###############################################################

source e2e-framework/configs/stacks.conf
if [[ $elastic_agent == true ]]; then
  pull_docker_file_of "elastic-agent"
  ./e2e-framework/configs/stacks/elastic-agent/elastic-agent.sh
  sleep 5
fi
# TODO: create a script (similar to elastic-agent.sh) under stacks folder to setup and run the docker file