# encoding: utf-8
ELASTIC_INTEGRATION_VERSION = File.read(File.expand_path(File.join(File.dirname(__FILE__), "VERSION"))).strip unless defined?(ELASTIC_INTEGRATION_VERSION)

Gem::Specification.new do |s|
  s.name = 'logstash-filter-elastic_integration'
  s.version = ELASTIC_INTEGRATION_VERSION
  s.licenses = ['NONE']
  s.summary = "Processes Elastic Integrations"
  s.description = "This gem is a Logstash plugin required to be installed on top of the Logstash core pipeline using $LS_HOME/bin/logstash-plugin install gemname. This gem is not a stand-alone program"
  s.authors = ["Elastic"]
  s.email = 'info@elastic.co'
  s.homepage = "http://www.elastic.co/guide/en/logstash/current/index.html"
  s.require_paths = ["lib", "vendor/jar-dependencies"]

  # Files
  s.files = Dir[*%w{
    lib/**/*.*
    *.gemspec
    vendor/jar-dependencies/**/*.jar
    VERSION
    LICENSE
  }]

  # Special flag to let us know this is actually a logstash plugin
  s.metadata = {
    "logstash_plugin" => "true",
    "logstash_group" => "filter",
  }

  # Gem dependencies
  s.add_runtime_dependency "logstash-core-plugin-api", ">= 1.60", "<= 2.99"

  s.add_development_dependency 'logstash-devutils'

  s.platform = "java"
end
