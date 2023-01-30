require "logstash/devutils/rspec/spec_helper"
require "logstash/devutils/rspec/shared_examples"

require "logstash/filters/elastic_integration"

describe LogStash::Filters::ElasticIntegration do
  let(:config) {{ }}
  let(:paths) do
    {
      # path has to be created, otherwise config :path validation fails
      # and since we cannot control the chmod operations on paths, we should stub file readable? and writable? operations
      :test_path => "spec/filters/resources/do_not_remove_path"
    }
  end

  subject(:plugin) { LogStash::Filters::ElasticIntegration.new(config) }

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

    it { is_expected.to have_attributes(:ssl => true) }
    it { is_expected.to have_attributes(:ssl_verification_mode => "full") }
  end

  context "plugin register" do
    let(:registered_plugin) { plugin.tap(&:register) }

    describe "when SSL enabled" do
      let(:config) { super().merge("ssl" => true) }

      describe "with `ssl_certificate`" do
        let(:config) { super().merge("ssl_certificate" => paths[:test_path]) }

        describe "with `keystore`" do
          let(:config) { super().merge("keystore" => paths[:test_path]) }

          it "doesn't allow to use together" do
            expected_message = "`ssl_certificate` and `keystore` cannot be used together."
            expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        it "requires `ssl_key`" do
          expected_message = "`ssl_certificate` requires `ssl_key`"
          expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
        end

        describe "with `ssl_key`" do
          let(:config) { super().merge("ssl_key" => paths[:test_path]) }

          it "requires `ssl_key_passphrase`" do
            expected_message = "`ssl_key` requires `ssl_key_passphrase`"
            expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end

          describe "with empty `ssl_key_passphrase`" do
            let(:config) { super().merge("ssl_key_passphrase" => "") }

            it "requires non-empty passphrase" do
              expected_message = "`ssl_key_passphrase` cannot be empty"
              expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end

          describe "with non-empty `ssl_key_passphrase`" do
            let(:config) { super().merge("ssl_key_passphrase" => "ssl_key_pa$$phrase") }

            describe "with non readable path" do
              before(:each) do
                allow(File).to receive(:readable?).and_return(false)
              end
              it "requires readable path" do
                expected_message = "Specified ssl_certificate spec/filters/resources/do_not_remove_path path must be readable."
                expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
              end
            end

            describe "with readable and writable path" do
              before(:each) do
                allow(File).to receive(:readable?).and_return(true)
                allow(File).to receive(:writable?).and_return(true)
              end
              it "requires non-writable path" do
                expected_message = "Specified ssl_certificate spec/filters/resources/do_not_remove_path path must not be writable."
                expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
              end
            end

          end
        end

      end

      describe "without `ssl_certificate`" do

        describe "with `ssl_key`" do
          let(:config) { super().merge("ssl_key" => paths[:test_path]) }

          it "requires `ssl_certificate`" do
            expected_message = "`ssl_key` is not allowed unless `ssl_certificate` is specified"
            expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "without `ssl_key`" do

          describe "with `ssl_key_passphrase`" do

            describe "with `ssl_key_passphrase`" do
              let(:config) { super().merge("ssl_key_passphrase" => "ssl_key_pa&$phrase") }

              it "requires `ssl_key`" do
                expected_message = "`ssl_key_passphrase` is not allowed unless `ssl_key` is specified"
                expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
              end
            end

          end
        end
      end

      describe "with `keystore`" do
        let(:config) { super().merge("keystore" => paths[:test_path]) }

        it "requires `keystore_password`" do
          expected_message = "`keystore` requires `keystore_password`"
          expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
        end

        describe "with empty `keystore_password`" do
          let(:config) { super().merge("keystore_password" => "") }

          it "doesn't allow empty `keystore_password`" do
            expected_message = "`keystore_password` cannot be empty"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "with non-empty `keystore_password`" do
          let(:config) { super().merge("keystore_password" => "keystore_pa$$word") }

          describe "with non readable path" do
            before(:each) do
              allow(File).to receive(:readable?).and_return(false)
            end
            it "requires readable path" do
              expected_message = "Specified keystore spec/filters/resources/do_not_remove_path path must be readable."
              expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end

          describe "with readable and writable path" do
            before(:each) do
              allow(File).to receive(:readable?).and_return(true)
              allow(File).to receive(:writable?).and_return(true)
            end
            it "requires non-writable path" do
              expected_message = "Specified keystore spec/filters/resources/do_not_remove_path path must not be writable."
              expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end
        end
        end

      describe "without `keystore`" do

        describe "with `keystore_password`" do
          let(:config) { super().merge("keystore_password" => "keystore_pa$$word") }

          it "doesn't allow" do
            expected_message = "`keystore_password` is not allowed unless `keystore` is specified"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

      end

      describe "with `ssl_verification_mode` is `none`" do
        let(:config) { super().merge("ssl_verification_mode" => "none") }

        describe "with `truststore`" do
          let(:config) { super().merge("truststore" => paths[:test_path]) }

          it "requires full or certificate `ssl_verification_mode`" do
            expected_message = "`truststore` requires either `full` or `certificate` of `ssl_verification_mode`"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "with `truststore_password`" do
          let(:config) { super().merge("truststore_password" => "truststore_pa$$word") }

          it "requires `truststore_password` and full or certificate `ssl_verification_mode`" do
            expected_message = "`truststore_password` requires `truststore` and `full` or `certificate` of `ssl_verification_mode`"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "with `ssl_certificate_authorities`" do
          let(:config) { super().merge("ssl_certificate_authorities" => [paths[:test_path]]) }

          it "requires full or certificate `ssl_verification_mode`" do
            expected_message = "`ssl_certificate_authorities` requires either `full` or `certificate` of `ssl_verification_mode`"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

      end

      describe "with `ssl_verification_mode` is not `none`" do
        let(:config) { super().merge("ssl_verification_mode" => "certificate") }

        describe "with `truststore` and `ssl_certificate_authorities`" do
          let(:config) { super().merge("truststore" => paths[:test_path], "ssl_certificate_authorities" => [paths[:test_path]]) }
          it "doesn't allow to use together" do
            expected_message = "`truststore` and `ssl_certificate_authorities` cannot be used together."
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "without `truststore` and empty `ssl_certificate_authorities`" do
          let(:config) { super().merge("ssl_certificate_authorities" => []) }
          it "doesn't allow empty `ssl_certificate_authorities`" do
            expected_message = "`ssl_certificate_authorities` cannot be empty"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "with `truststore`" do
          let(:config) { super().merge("truststore" => paths[:test_path]) }

          it "requires `truststore_password`" do
            expected_message = "`truststore` requires `truststore_password`"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end

          describe "with empty `truststore_password`" do
            let(:config) { super().merge("truststore_password" => "") }

            it "doesn't accept empty `truststore_password`" do
              expected_message = "`truststore_password` cannot be empty"
              expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end

          describe "with non-empty `truststore_password`" do
            let(:config) { super().merge("truststore_password" => "truststore_pa$$word") }

            describe "with non readable path" do
              before(:each) do
                allow(File).to receive(:readable?).and_return(false)
              end
              it "requires readable path" do
                expected_message = "Specified truststore spec/filters/resources/do_not_remove_path path must be readable."
                expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
              end
            end

            describe "with readable and writable path" do
              before(:each) do
                allow(File).to receive(:readable?).and_return(true)
                allow(File).to receive(:writable?).and_return(true)
              end
              it "requires non-writable path" do
                expected_message = "Specified truststore spec/filters/resources/do_not_remove_path path must not be writable."
                expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
              end
            end
          end
        end

        describe "with `truststore_password`" do
          let(:config) { super().merge("truststore_password" => "truststore_pa$$word") }

          it "requires `truststore`" do
            expected_message = "`truststore_password` is not allowed unless `truststore` is specified"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "with `ssl_certificate_authorities`" do
          let(:config) { super().merge("ssl_certificate_authorities" => [paths[:test_path]]) }

          describe "with non readable path" do
            before(:each) do
              allow(File).to receive(:readable?).and_return(false)
            end
            it "requires readable path" do
              expected_message = "Specified ssl_certificate_authorities spec/filters/resources/do_not_remove_path path must be readable."
              expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end

          describe "with readable and writable path" do
            before(:each) do
              allow(File).to receive(:readable?).and_return(true)
              allow(File).to receive(:writable?).and_return(true)
            end
            it "requires non-writable path" do
              expected_message = "Specified ssl_certificate_authorities spec/filters/resources/do_not_remove_path path must not be writable."
              expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end
        end
      end

      describe "connection prerequisites" do

        describe "with both `hosts` and `cloud_id`" do
          let(:config) {super().merge("hosts" => ["https://my-es-host:9200"], "cloud_id" => "my_cloud_id")}

          it "requires either of them" do
            expected_message = "`hosts` and `cloud_id` cannot be used together."
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "with `hosts`" do

          describe "with HTTP scheme" do
            let(:config) { super().merge("hosts" => ["http://my-es-cluster:1111", "http://cloud-test.es.us-west-2.aws.found.io"])}

            it "enforces to agree with scheme" do
              expected_message = "All hosts must agree with https schema when  using `ssl`."
              expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)

            end
          end

          describe "with HTTPS scheme" do
            let(:config) { super().merge("hosts" => ["https://my-es-cluster:1111", "https://my-another-es-cluster:2222", "https://cloud-test.es.us-west-2.aws.found.io"])}

            it "accepts" do
              expect{ registered_plugin }.not_to raise_error
            end
          end

        end
      end
    end

    describe "when SSL disabled" do
      let(:config) { super().merge("ssl" => false) }

      describe "when `ssl_verification_mode` is not `none`" do
        let(:config) { super().merge("ssl_verification_mode" => "certificate") }

        it "raises an error" do
          expected_message = "`ssl_verification_mode` certificate requires `ssl` enabled"
          expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
        end
      end

      describe "wen SSL related configs are specified" do
        let(:config) { super().merge("ssl_verification_mode" => "none", "keystore" => paths[:test_path], "cloud_id" => "my-es-cloud:id_", "ssl_certificate_authorities" => [paths[:test_path]]) }

        it "does not allow and raises an error" do
          expected_message = 'When ssl is disabled, the following provided parameters are not allowed: ["keystore", "cloud_id", "ssl_certificate_authorities"]'
          expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
        end
      end

      context "connection prerequisites" do
        let(:config) { super().merge("ssl_verification_mode" => "none") }

        describe "when no `hosts` or `cloud_id is specified" do
          it "requires either of them" do
            expected_message = "Either `hosts` or `cloud_id` is required"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "when either `hosts` or `cloud_id is specified" do
          let(:config) { super().merge("hosts" => ["http://my-es-cluster:1111", "http://cloud-test.es.us-west-2.aws.found.io"]) }
          it "accepts" do
            expect{ registered_plugin }.not_to raise_error
          end
        end

        describe "when multiple auth options are specified" do
          let(:config) {super().merge("cloud_auth" => "my_cloud_auth", "api_key" => "api_key", "hosts" => ["http://my-es-cluster:1111"])}

          it "does not allow" do
            expected_message = 'Multiple authentication ["cloud_auth", "api_key"] options cannot be used together. Please provide ONLY one.'
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "with `hosts`" do

          describe "with HTTP scheme" do
            let(:config) { super().merge("hosts" => ["http://my-es-cluster:1111", "http://cloud-test.es.us-west-2.aws.found.io"])}

            it "accepts" do
              expect{ registered_plugin }.not_to raise_error
            end
          end

          describe "with HTTPS scheme" do
            let(:config) { super().merge("hosts" => ["http://my-es-cluster:1111", "https://my-another-es-cluster:2222", "https://cloud-test.es.us-west-2.aws.found.io"])}

            it "enforces to agree with scheme" do
              expected_message = "All hosts must agree with http schema when NOT using `ssl`."
              expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end
        end

        describe "with basic auth" do

          describe "with `auth_basic_username`" do
            let(:config) { super().merge("auth_basic_username" => "test_user") }

            it "requires `auth_basic_password`" do
              expected_message = "`auth_basic_username` requires `auth_basic_password`"
              expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end

          describe "with `auth_basic_password`" do
            let(:config) { super().merge("auth_basic_password" => "pa$$") }

            it "requires `auth_basic_username`" do
              expected_message = "`auth_basic_password` is not allowed unless `auth_basic_username` is specified"
              expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end
        end
      end
    end

  end
end