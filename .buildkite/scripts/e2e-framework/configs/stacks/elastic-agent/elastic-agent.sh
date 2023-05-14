################### Elastic agent #############################
# create agent docker container
docker create --name elastic-agent-container --network elastic-package-stack_default -p 8220:8220 \
		--env FLEET_SERVER_ENABLE=false \
		-h elastic-agent-host \
	 	docker.elastic.co/beats/elastic-agent:"${ELASTIC_STACK_VERSION}"

docker cp e2e-framework/configs/stacks/elastic-agent/elastic-agent.yml elastic-agent-container:/usr/share/elastic-agent
docker start elastic-agent-container

###############################################################