#!/bin/bash

set -e

# resolve latest elastic stack version from n.x (ex, 8.x). Resolved version would be 8.7.0
./e2e-framework/resolve_stack_version.sh

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
pull_docker_file_of "elastic-agent" # TODO: receive an array for list of required stacks to pull

################### Built plugin ##############################
# build the plugin, TODO: make this script external as every plugin has its own rake executions
cd .. && cd ..
#bundle install
#bundle exec rake prepare_geoip_resources
#bundle exec rake install_jars
cd .buildkite && cd scripts

###############################################################

################### Elastic stack #############################
# run ES, KB and ERP
./elastic-package stack up –version "${ELASTIC_STACK_VERSION}" --services package-registry,kibana &

# copy certs from elasticsearch-1 container, this is required when connecting to ES from LS
rm -rf tmp
mkdir tmp
docker cp elastic-package-stack-elasticsearch-1:/usr/share/elasticsearch/config/certs tmp
###############################################################

################### Logstash ##################################
# create a logstash-container docker container
docker create --name logstash-container --network elastic-package-stack_default \
  -p 9600:9600 -p 5044:5044/tcp \
  docker.elastic.co/logstash/logstash:"${ELASTIC_STACK_VERSION}"

# copy ES certificates
docker cp tmp/certs logstash-container:/usr/share/logstash/config

# run logstash-container, copy locally built plugin to container and install plugin
docker start logstash-container &
sleep 10 # TODO: use health API instead
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

docker stop logstash-container
sleep 5
docker start logstash-container &
sleep 5
###############################################################

################### Elastic agent #############################
# create agent docker container
docker create --name elastic-agent-container --network elastic-package-stack_default -p 8220:8220 \
		--env FLEET_SERVER_ENABLE=false \
	 	docker.elastic.co/beats/elastic-agent:"${ELASTIC_STACK_VERSION}"

docker cp e2e-framework/configs/elastic-agent.yml elastic-agent-container:/usr/share/elastic-agent
docker start elastic-agent-container

###############################################################