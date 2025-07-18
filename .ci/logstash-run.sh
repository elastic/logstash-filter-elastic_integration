#!/bin/bash

env

set -ex

if [[ "$SECURE_INTEGRATION" == "true" ]]; then
  ES_URL="https://elasticsearch:9200"
else
  ES_URL="http://elasticsearch:9200"
fi

# CentOS 7 using curl defaults does not enable TLSv1.3
CURL_OPTS="-k --tlsv1.2 --tls-max 1.3"

wait_for_es() {
  count=120
  while ! curl $CURL_OPTS $ES_URL >/dev/null && [[ $count -ne 0 ]]; do
    count=$(( $count - 1 ))
    [[ $count -eq 0 ]] && exit 1
    sleep 1
  done
  echo $(curl $CURL_OPTS -vi $ES_URL | python -c "import sys, json; print(json.load(sys.stdin)['version']['number'])")
}

bundle exec rake prepare_geoip_resources

if [[ "$INTEGRATION" != "true" ]]; then
  bundle exec rspec --format=documentation spec/unit --tag ~integration --tag ~secure_integration
  bundle exec rake java_test
else

  if [[ "$SECURE_INTEGRATION" == "true" ]]; then
    extra_tag_args="--tag secure_integration"
  else
    extra_tag_args="--tag ~secure_integration --tag integration"
  fi

  echo "Waiting for elasticsearch to respond..."
  ES_VERSION=$(wait_for_es)
  echo "Elasticsearch $ES_VERSION is Up!"
  chmod -R 0440 spec/fixtures/test_certs/*
  bundle exec rspec --format=documentation $extra_tag_args --tag es_version:$ES_VERSION spec/integration
fi