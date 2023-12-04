if [ -z "$ELASTICSEARCH_TREEISH" ]; then
  source .buildkite/scripts/resolve_es_branch.sh
  echo "Resolved ELASTICSEARCH_TREEISH: ${ELASTICSEARCH_TREEISH}"
else
  echo "ELASTICSEARCH_TREEISH ${ELASTICSEARCH_TREEISH} is defined."
fi

mkdir -p .ci && curl -sL https://github.com/logstash-plugins/.ci/archive/buildkite-1.x.tar.gz | tar zxvf - --skip-old-files --strip-components=1 -C .ci --wildcards '*Dockerfile*' '*docker*' '*.sh' && .ci/docker-setup.sh && .ci/docker-run.sh