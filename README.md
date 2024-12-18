# Elastic Integration plugin for Logstash

This is a plugin for [Logstash](https://github.com/elastic/logstash), and is not meant to be used on its own.

It can be used in a Logstash pipeline to perform the transformations that are applied by many [Elastic Integrations](https://www.elastic.co/integrations/data-integrations) _before_ sending the events to Elasticsearch.

## Documentation

Logstash provides infrastructure to automatically generate documentation for this plugin from its [asciidoc source](docs/index.asciidoc).
All plugin documentation are placed under one [central location](http://www.elastic.co/guide/en/logstash/current/).

## Development
### Prerequisites
- Clone the [plugin repo](https://github.com/elastic/logstash-filter-elastic_integration)
- Check out the branch you would like to build, such as `8.x` or `9.x/main`, etc...
  - Note that, `8.series` branches require JDK17 and `9.series`/`main` requires JDK21.
- Set the Logstash source path in the `gradle.properties` file. Open the file and update the `LOGSTASH_PATH`.
- Make sure Logstash is compiled.
- Point to the Elasticsearch branch/version to build the plugin with. Open the `gradle.properties` file and update `ELASTICSEARCH_TREEISH`.
- Point to the Elasticsearch branch/version to build the plugin with. Open the `gradle.properties` file and update `ELASTICSEARCH_TREEISH`.

### Build
We use Gradle tool to build this plugin. Gradle seeks Logstash core jars from the `LOGSTASH_PATH` path, downloads the Elasticsearch version defined with `ELASTICSEARCH_TREEISH` and build the plugin.
The build process may fail if the Elasticsearch interfaces the plugin is using have changed.

The following command builds plugin and generates the jar file, can be locally installed in Logstash core and verified:
```shell
./gradlew clean vendor localGem
```

### Plugin test
The recommendation to run tests to align on our common [`plugins/.ci`](https://github.com/logstash-plugins/.ci) tool.
Run the following command under the plugin source dir to download the CI tool:
```shell
mkdir -p .ci-test && curl -sL --retry 5 --retry-delay 5 https://github.com/logstash-plugins/.ci/archive/1.x.tar.gz | tar zxvf - --keep-old-files --strip-components=1 -C .ci-test
```

Export environment variables:
```shell
SNAPSHOT=false                # set to true if targeting the SNAPSHOT
ELASTIC_STACK_VERSION=8.17.0  # stack version to run the plugin on
INTEGRATION=false             # set to true if you want integration tests to run, it also requires SECURE_INTEGRATION=true
```

Run tests on the local docker environment, make sure your docker-engine is running.
```shell
./.ci/docker-setup.sh && ./.ci/docker-run.sh
```

### Running your unpublished plugin in Logstash

#### Run in a local Logstash clone
- Edit Logstash `Gemfile` and add the local plugin path, for example:
```ruby
gem "logstash-filter-awesome", :path => "/your/local/logstash-filter-awesome"
```
- Install plugin
```sh
bin/logstash-plugin install --no-verify
```
- Run Logstash with your plugin
```sh
bin/logstash -e 'filter {awesome {}}'
```
At this point any modifications to the plugin code will be applied to this local Logstash setup. After modifying the plugin, simply rerun Logstash.

#### Run in an installed Logstash
- Edit Logstash `Gemfile` and add the local plugin path, for example:
```ruby
gem "logstash-filter-awesome", :path => "/your/local/logstash-filter-awesome"
```
- Build your plugin gem
```sh
./gradlew clean vendor localGem
```
- Install the plugin from the Logstash home
```sh
bin/logstash-plugin install --no-verify
```
- Start Logstash and proceed to test the plugin