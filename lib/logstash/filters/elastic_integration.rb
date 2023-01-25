# encoding: utf-8
require "logstash/filters/base"
require "logstash/namespace"

class LogStash::Filters::ElasticIntegration < LogStash::Filters::Base
  config_name "elastic_integration"

  def register
    # bootstrap placeholder no-op
  end # def register

  def filter(event)
    # bootstrap placeholder no-op
  end
end