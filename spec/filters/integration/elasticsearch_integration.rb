
describe 'Elasticsearch has index lifecycle management enabled', :integration => true do

  let (:settings) {
    {
      "hosts" => "elasticsearch:9200"
    }
  }

  subject(:elastic_integration_plugin) { LogStash::Filters::ElasticIntegration.new(settings) }


  # create an ingest pipeline
  # make sure plugin loads created pipeline when registered?
end
