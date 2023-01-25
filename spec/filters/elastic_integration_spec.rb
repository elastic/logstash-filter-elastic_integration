require "logstash/devutils/rspec/spec_helper"
require "logstash/devutils/rspec/shared_examples"

require "logstash/filters/elastic_integration"

describe LogStash::Filters::ElasticIntegration do
  describe 'the plugin class' do
    subject { described_class }
    it { is_expected.to be_a_kind_of Class }
    it { is_expected.to be <= LogStash::Filters::Base }
    it { is_expected.to have_attributes(:config_name => "elastic_integration") }
  end
  describe 'an instance with default config' do
    subject(:instance) { described_class.new({}) }

    it { is_expected.to be_a_kind_of LogStash::Filters::Base }
    it { is_expected.to respond_to(:register).with(0).arguments }
    it { is_expected.to respond_to(:filter).with(1).argument }
  end
end