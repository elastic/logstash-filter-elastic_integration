# encoding: utf-8
ELASTIC_INTEGRATION_VERSION = File.read(File.expand_path(File.join(File.dirname(__FILE__), "VERSION"))).strip unless defined?(ELASTIC_INTEGRATION_VERSION)

Gem::Specification.new do |s|
  s.name = 'logstash-filter-elastic_integration'
  s.version = ELASTIC_INTEGRATION_VERSION
  s.licenses = ['ELv2']
  s.summary = "Processes Elastic Integrations"
  s.description = "This gem is a Logstash plugin required to be installed on top of the Logstash core pipeline using $LS_HOME/bin/logstash-plugin install gemname. This gem is not a stand-alone program"
  s.authors = ["Elastic"]
  s.email = 'info@elastic.co'
  s.homepage = "https://www.elastic.co/logstash"
  s.require_paths = %w[lib vendor/jar-dependencies]

  # Files to be included in package
  s.files = Dir[*%w{
    lib/**/*.*
    *.gemspec
    vendor/jar-dependencies/**/*.jar
    VERSION
    LICENSE.md
    NOTICE.txt
  }]

  # Special flag to let us know this is actually a logstash plugin
  s.metadata = {
    "logstash_plugin" => "true",
    "logstash_group" => "filter",
    "source_code_uri" => "https://github.com/elastic/logstash-filter-elastic_integration",
  }

  # Gem dependencies
  s.add_runtime_dependency "logstash-core-plugin-api", ">= 1.60", "<= 2.99"
  s.add_runtime_dependency "logstash-core", ">= 8.7.0"

  s.add_development_dependency 'logstash-devutils'

  s.platform = "java"

  s.post_install_message = <<~NOTICES
    This Logstash plugin embeds a subset of Elasticsearch (https://elastic.co/)
    and packages from Apache Lucene, including software developed by The Apache
    Software Foundation (http://www.apache.org/).
  NOTICES
end
