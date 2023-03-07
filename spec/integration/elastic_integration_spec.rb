#classpath = ::System::getProperty("java.class.path")
#classpath = java.lang.System::getProperties
#puts "Class path: #{classpath}"

require 'manticore'
require 'logstash/filters/elastic_integration'

describe 'Elasticsearch has index lifecycle management enabled', :integration => true do

  let(:settings) {
    {
      #"hosts" => "elasticsearch:9200"
      # https://[username]:[password]@[host]:[port]/
      "host" => "https://elastic:srYYH52WiYMQ7NtApVDeflMb@cloud-test.es.us-west-2.aws.found.io:443"
    }
  }

  let(:index_settings) {
    {
      "type" => "integration-test",
      "dataset" => "",
      "namespace" => ""
    }
  }

  subject(:elastic_integration_plugin) { LogStash::Filters::ElasticIntegration.new(settings) }
  let(:es_http_client) { Manticore::Client.new }

  before(:each) do
    # create an ingest pipeline
    # create an index template by setting default pipeline
    ingest_url = settings["host"] + "/_ingest/pipeline/logstash-integration-test"
    puts "Host: #{ingest_url}"
    es_http_client.delete(ingest_url)
  end

  after(:each) do
    # remove ingest pipeline
    # remove an index template
  end

  it 'should test' do
    #subject.register
    expect(true).to be true
  end

  context "Loads ingest pipelines" do


  end

  # create an ingest pipeline
  #   need ES client
  # ASSERT: plugin loads created pipeline

  ################################################################################################
  # To create a data stream index with ingest pipeline, do following steps:
  # 1. Create ingest pipeline with multiple processors
  # PUT _ingest/pipeline/integration-tests-ingest-pipeline-1
  # {
  #   "description": "Logstash Integration Test Ingest Pipeline",
  #   "processors": [
  #     {
  #       "set": {
  #         "field": "elephant_age",
  #         "value": 120
  #       }
  #     },
  #     {
  #       "lowercase": {
  #         "field": "home" # cage
  #       }
  #     }
  #   ]
  # }

  # 2. Create an index template with data-stream and default pipeline
  # PUT _index_template/logs-ingest-test-index-template-1
  # {
  #   "index_patterns": ["logs-ingest-pipeline1-*"],
  #   "data_stream": { },
  #   "template": {
  #     "settings": {
  #       "index.default_pipeline": "integration-tests-ingest-pipeline-1",
  #       "index.lifecycle.name": "logs"
  #     }
  #   }
  # }
  # Delete when test is finished
  #   DELETE _index_template/logs-ingest-test-index-template-1

  # 3. Send data to Logstash

  # 4. Get outcome from Logstash and assert with expectation

  ################################################

  # enrich processors {enrich-*}
end
