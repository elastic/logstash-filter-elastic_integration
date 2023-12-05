if [ -z "$ELASTICSEARCH_TREEISH" ]; then
  source .buildkite/scripts/resolve_es_treeish.sh
  echo "Resolved ELASTICSEARCH_TREEISH: ${ELASTICSEARCH_TREEISH}"
else
  echo "Using ELASTICSEARCH_TREEISH ${ELASTICSEARCH_TREEISH} defined in the ENV."
fi

mkdir -p .ci && curl -sL --retry 5 --retry-delay 5 https://github.com/logstash-plugins/.ci/archive/buildkite-1.x.tar.gz | tar zxvf - --skip-old-files --strip-components=1 -C .ci --wildcards '*Dockerfile*' '*docker*' '*.sh' && .ci/docker-setup.sh && .ci/docker-run.sh