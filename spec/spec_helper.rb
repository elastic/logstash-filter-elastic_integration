require "rspec"
require "rspec/mocks"
require "logstash/devutils/rspec/spec_helper"

module SpecHelper

  def get_host_port_for_es_client
    if ENV["INTEGRATION"] == "true"
      host = "admin:elastic@elasticsearch:9200"
      ENV["SECURE_INTEGRATION"] == "true" ? "https://" + host : "http://" + host
    else
      "http://localhost:9200"
    end
  end
end

RSpec.configure do |config|
  config.include SpecHelper
end