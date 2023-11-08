require "rspec"
require "rspec/mocks"
require "logstash/devutils/rspec/spec_helper"

# Since we use Logstash's x-pack WITHOUT the LogStash::Runner,
# we must find it relative to logstash-core and add it to the load path.
require 'pathname'
logstash_core_path = Gem.loaded_specs['logstash-core']&.full_gem_path or fail("logstash-core lib not found")
logstash_xpack_load_path = Pathname.new(logstash_core_path).join("../x-pack/lib").cleanpath.to_s
unless $LOAD_PATH.include?(logstash_xpack_load_path)
  $stderr.puts("ADDING LOGSTASH X-PACK to load path: #{logstash_xpack_load_path}")
  $LOAD_PATH.unshift(logstash_xpack_load_path)
end

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