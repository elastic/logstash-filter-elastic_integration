#!/bin/bash

set -e

cd .. && cd ..
bundle install
bundle exec rake prepare_geoip_resources
bundle exec rake install_jars
cd .buildkite && cd scripts