#!/usr/bin/env bash

export JAVA_HOME="/opt/buildkite-agent/.java/adoptiumjdk_21"
export PATH="/opt/buildkite-agent/.rbenv/bin:/opt/buildkite-agent/.pyenv/bin:/opt/buildkite-agent/.java/bin:$JAVA_HOME:$PATH"
eval "$(rbenv init -)"

if [ -z "$TARGET_BRANCH" ]; then
  echo "Target branch is not specified, using default branch: main or BK defined"
else
  echo "Changing the branch for ${TARGET_BRANCH}"
  git checkout "$TARGET_BRANCH"
fi

<<<<<<< HEAD
mkdir -p .ci && curl -sL --retry 5 --retry-delay 5 https://github.com/logstash-plugins/.ci/archive/1.x.tar.gz | tar zxvf - --skip-old-files --strip-components=1 -C .ci --wildcards '*Dockerfile*' '*docker*' '*.sh' '*logstash-versions*' && .ci/docker-setup.sh && .ci/docker-run.sh
=======
./utils/ssl-certs-generator/generate.sh
chmod -R 0440 utils/ssl-certs-generator/generated/*
mkdir -p .ci && curl -sL --retry 5 --retry-delay 5 https://github.com/logstash-plugins/.ci/archive/1.x.tar.gz | tar zxvf - --skip-old-files --strip-components=1 -C .ci --wildcards '*Dockerfile*' '*docker*' '*.sh' '*logstash-versions*' && .ci/docker-setup.sh && .ci/docker-run.sh
>>>>>>> 254e669 (Autorenew SSL certs and use them everywhere (#431))
