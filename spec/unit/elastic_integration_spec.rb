require "logstash/devutils/rspec/spec_helper"
require "logstash/devutils/rspec/shared_examples"

require "logstash/filters/elastic_integration"

describe LogStash::Filters::ElasticIntegration do
  let(:config) {{ }}
  let(:paths) do
    {
      # path has to be created, otherwise config :path validation fails
      # and since we cannot control the chmod operations on paths, we should stub file readable? and writable? operations
      :test_path => "spec/unit/resources/do_not_remove_path"
    }
  end

  subject(:plugin) { LogStash::Filters::ElasticIntegration.new(config) }

  let(:mock_geoip_database_manager) { double("LogStash::GeoipDatabaseManagement::Manager", :enabled? => false) }
  before(:each) {
    allow(plugin).to receive(:load_geoip_database_manager!).and_return(mock_geoip_database_manager)
    allow(plugin).to receive(:logger).and_return(mock_logger)
  }

  let(:mock_logger) { double("Logger").as_null_object }

  describe 'the plugin class' do
    subject { described_class }
    it { is_expected.to be_a_kind_of Class }
    it { is_expected.to be <= LogStash::Filters::Base }
    it { is_expected.to have_attributes(:config_name => "elastic_integration") }

    context 'on unsupported Java' do
      before(:each) do
        allow(java.lang.System).to receive(:getProperty).with("java.specification.version").and_return("11.0.16.1")
      end

      it 'prevents initialization and presents helpful guidance' do
        expect { described_class.new({}) }.to raise_error(LogStash::EnvironmentError)
                                                .with_message(including("requires Java 21 or later", # reason +
                                                                        "current JVM version `11.0.16.1`",
                                                                        "remove the plugin", # guidance options
                                                                        "run Logstash with a supported JVM."))
      end
    end
  end

  describe 'an instance with default config' do
    subject(:instance) { described_class.new({}) }

    it { is_expected.to be_a_kind_of LogStash::Filters::Base }
    it { is_expected.to respond_to(:register).with(0).arguments }
    it { is_expected.to respond_to(:filter).with(1).argument }
  end

  # NOTE: Depends on LogStash::OSS, which is set by the bootstrap prelude for LogStash::Runner
  context 'Logstash core requirements' do
    context 'on OSS-only Logstash' do
      before(:each) { stub_const('LogStash::OSS', true) }
      it 'cannot be instantiated' do
        expect { described_class.new({}) }.to raise_error(LogStash::ConfigurationError).with_message(including "REQUIRES the complete Logstash distribution")
      end
    end
    context 'on complete Logstash' do
      before(:each) { stub_const('LogStash::OSS', false) }
      it 'can be instantiated' do
        described_class.new({})
      end
    end
  end

  describe "plugin register" do

    before(:each) { allow(plugin).to receive(:connected_es_version_info).and_return(connected_es_version_info) }
    before(:each) { allow(plugin).to receive(:check_user_privileges!).and_return(true) }
    before(:each) { allow(plugin).to receive(:check_es_cluster_license!).and_return(true) }
    before(:each) { allow(plugin).to receive(:serverless?).and_return(false) }

    let(:registered_plugin) { plugin.tap(&:register) }
    after(:each) { plugin.close }

    let(:connected_es_version_info) { {'number' => '8.17.0', 'build_flavor' => 'default'} }

    shared_examples "validate `ssl_enabled`" do
      it "enables SSL" do
        expect(registered_plugin.ssl_enabled).to be_truthy
      end
    end

    shared_examples "validate ssl_verification_mode" do
      it "has ssl_verification_mode => full" do
        expect(registered_plugin).to have_attributes(:ssl_verification_mode => "full")
      end
    end

    describe "infer SSL from connection settings" do

      context "with `cloud_id`" do
        let(:config) { super().merge("cloud_id" => "foobar:dXMtZWFzdC0xLmF3cy5mb3VuZC5pbyRub3RhcmVhbCRpZGVudGlmaWVy") }

        include_examples "validate `ssl_enabled`"
        include_examples "validate ssl_verification_mode"
      end

      describe "`hosts`" do

        context "with HTTPS protocol entry" do
          let(:config) { super().merge("hosts" => %w[https://127.0.0.1 https://127.0.0.2:9200/ https://my-es-cluster.com/]) }

          include_examples "validate `ssl_enabled`"
          include_examples "validate ssl_verification_mode"
        end

        context "with HTTP protocol entry" do
          let(:config) { super().merge("hosts" => %w[http://127.0.0.1 http://127.0.0.2:9200 http://my-es-cluster.com]) }

          it "disables the SSL" do
            expect(registered_plugin.ssl_enabled).to be_falsey
          end
        end

        context "with empty entry protocol" do
          let(:config) { super().merge("hosts" => %w[127.0.0.1 127.0.0.2:9200 my-es-cluster.com]) }

          include_examples "validate `ssl_enabled`"
          include_examples "validate ssl_verification_mode"
        end

        context "with mixed entries protocol" do
          let(:config) { super().merge("hosts" => %w[https://127.0.0.1 127.0.0.2:9200 http://my-es-cluster.com]) }

          it "raises an error" do
            expected_message = "`hosts` contains entries with mixed protocols, which are unsupported; when any entry includes a protocol, the protocols of all must match each other"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        context "with multiple paths" do
          let(:config) { super().merge("hosts" => %w[http://127.0.0.1/a-path http://127.0.0.2:9200/b-path http://my-es-cluster.com/c-path]) }

          it "raises an error" do
            expected_message = "All hosts must use same path."
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end
      end
    end

    describe "connection prerequisites" do

      context "with both `hosts` and `cloud_id`" do
        let(:config) {super().merge("hosts" => ["https://my-es-host:9200"], "cloud_id" => "my_cloud_id")}

        it "raises an error" do
          expected_message = "`hosts` and `cloud_id` cannot be used together."
          expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
        end
      end

      describe "`hosts`" do

        describe "HTTP scheme" do
          let(:config) { super().merge("hosts" => %w[http://my-es-cluster:1111 http://cloud-test.es.us-west-2.aws.found.io])}

          context "with SSL enabled" do
            let(:config) { super().merge("ssl_enabled" => true)}

            it "enforces to use HTTPS" do
              expected_message = "All hosts must agree with https schema when using `ssl_enabled`."
              expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end

          context "with SSL disabled" do
            let(:config) { super().merge("ssl_enabled" => false)}

            it "accepts" do
              expect{ registered_plugin }.not_to raise_error
            end
          end
        end

        context "with HTTPS scheme" do
          let(:config) { super().merge("hosts" => %w[https://my-es-cluster:1111 https://my-another-es-cluster:2222 https://cloud-test.es.us-west-2.aws.found.io])}

          describe "when SSL enabled" do
            let(:config) { super().merge("ssl_enabled" => true)}

            it "accepts" do
              expect{ registered_plugin }.not_to raise_error
            end
          end

          describe "when SSL disabled" do
            let(:config) { super().merge("ssl_enabled" => false)}

            it "raises an error" do
              expected_message = "All hosts must agree with http schema when NOT using `ssl_enabled`."
              expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end

        end
      end
    end

    describe "normalize `hosts`" do
      let(:config) { super().merge("hosts" => %w[my-es-cluster.com 127.0.0.1 127.0.0.2:9300]) }

      context "with SSL enabled" do
        let(:config) { super().merge("ssl_enabled" => true) }

        it "applies default value" do
          expect(registered_plugin.hosts[0].eql?(::LogStash::Util::SafeURI.new("https://my-es-cluster.com:9200/"))).to be_truthy
          expect(registered_plugin.hosts[1].eql?(::LogStash::Util::SafeURI.new("https://127.0.0.1:9200/"))).to be_truthy
          expect(registered_plugin.hosts[2].eql?(::LogStash::Util::SafeURI.new("https://127.0.0.2:9300/"))).to be_truthy
        end
      end

      context "with SSL disabled" do
        let(:config) { super().merge("ssl_enabled" => false) }

        it "applies default value" do
          expect(registered_plugin.hosts[0].eql?(::LogStash::Util::SafeURI.new("http://my-es-cluster.com:9200/"))).to be_truthy
          expect(registered_plugin.hosts[1].eql?(::LogStash::Util::SafeURI.new("http://127.0.0.1:9200/"))).to be_truthy
          expect(registered_plugin.hosts[2].eql?(::LogStash::Util::SafeURI.new("http://127.0.0.2:9300/"))).to be_truthy
        end
      end

      context "with no SSL specified" do

        include_examples "validate `ssl_enabled`"
        include_examples "validate ssl_verification_mode"

        it "applies default value" do
          expect(registered_plugin.hosts[0].eql?(::LogStash::Util::SafeURI.new("https://my-es-cluster.com:9200/"))).to be_truthy
          expect(registered_plugin.hosts[1].eql?(::LogStash::Util::SafeURI.new("https://127.0.0.1:9200/"))).to be_truthy
          expect(registered_plugin.hosts[2].eql?(::LogStash::Util::SafeURI.new("https://127.0.0.2:9300/"))).to be_truthy
        end
      end
    end

    context "with SSL enabled" do
      let(:config) { super().merge("ssl_enabled" => true, "cloud_id" => "my-es-cloud-id:dXMtZWFzdC0xLmF3cy5mb3VuZC5pbyRub3RhcmVhbCRpZGVudGlmaWVy") }

      include_examples "validate ssl_verification_mode"

      context "with `ssl_certificate`" do
        let(:config) { super().merge("ssl_certificate" => paths[:test_path]) }

        describe "with `ssl_keystore_path`" do
          let(:config) { super().merge("ssl_keystore_path" => paths[:test_path]) }

          it "doesn't allow to use together" do
            expected_message = "`ssl_certificate` and `ssl_keystore_path` cannot be used together."
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
            allow(File).to receive(:writable?).and_return(false)
            expected_message = "`ssl_key` requires `ssl_key_passphrase`"
            expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end

          describe "with empty `ssl_key_passphrase`" do
            let(:config) { super().merge("ssl_key_passphrase" => "") }

            it "requires non-empty passphrase" do
              allow(File).to receive(:writable?).and_return(false)
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
                expected_message = "Specified ssl_certificate #{paths[:test_path]} path must be readable."
                expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
              end
            end

            describe "with readable and writable path" do
              before(:each) do
                allow(File).to receive(:readable?).and_return(true)
                allow(File).to receive(:writable?).and_return(true)
              end
              it "requires non-writable path" do
                expected_message = "Specified ssl_certificate #{paths[:test_path]} path must not be writable."
                expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
              end
            end
          end
        end
      end

      context "without `ssl_certificate`" do

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

      context "with `ssl_keystore_path`" do
        let(:config) { super().merge("ssl_keystore_path" => paths[:test_path]) }

        it "requires `ssl_keystore_password`" do
          expected_message = "`ssl_keystore_path` requires `ssl_keystore_password`"
          expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
        end

        describe "with empty `ssl_keystore_password`" do
          let(:config) { super().merge("ssl_keystore_password" => "") }

          it "doesn't allow empty `ssl_keystore_password`" do
            expected_message = "`ssl_keystore_password` cannot be empty"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "with non-empty `ssl_keystore_password`" do
          let(:config) { super().merge("ssl_keystore_password" => "keystore_pa$$word") }

          describe "with non readable path" do
            before(:each) do
              allow(File).to receive(:readable?).and_return(false)
            end
            it "requires readable path" do
              expected_message = "Specified ssl_keystore_path #{paths[:test_path]} path must be readable."
              expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end

          describe "with readable and writable path" do
            before(:each) do
              allow(File).to receive(:readable?).and_return(true)
              allow(File).to receive(:writable?).and_return(true)
            end
            it "requires non-writable path" do
              expected_message = "Specified ssl_keystore_path #{paths[:test_path]} path must not be writable."
              expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end
        end
        end

      context "without `ssl_keystore_path`" do

        describe "with `ssl_keystore_password`" do
          let(:config) { super().merge("ssl_keystore_password" => "keystore_pa$$word") }

          it "doesn't allow" do
            expected_message = "`ssl_keystore_password` is not allowed unless `ssl_keystore_path` is specified"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

      end

      describe "with `ssl_verification_mode` is `none`" do
        let(:config) { super().merge("ssl_verification_mode" => "none") }

        describe "with `ssl_truststore_path`" do
          let(:config) { super().merge("ssl_truststore_path" => paths[:test_path]) }

          it "requires full or certificate `ssl_verification_mode`" do
            expected_message = "`ssl_truststore_path` requires `ssl_verification_mode` to be either `full` or `certificate`"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "with `ssl_truststore_password`" do
          let(:config) { super().merge("ssl_truststore_password" => "truststore_pa$$word") }

          it "requires `ssl_truststore_password` and full or certificate `ssl_verification_mode`" do
            expected_message = "`ssl_truststore_password` requires `ssl_truststore_path` and `ssl_verification_mode` (either `full` or `certificate`)"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "with `ssl_certificate_authorities`" do
          let(:config) { super().merge("ssl_certificate_authorities" => [paths[:test_path]]) }

          it "requires full or certificate `ssl_verification_mode`" do
            expected_message = "`ssl_certificate_authorities` requires `ssl_verification_mode` to be either `full` or `certificate`"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "with `ssl_enabled`" do
          let(:config) { super().merge("ssl_enabled" => true) }

          it "establishes a connection" do
            expect{ registered_plugin }.not_to raise_error
          end
        end

      end

      describe "with `ssl_verification_mode` is not `none`" do
        let(:config) { super().merge("ssl_verification_mode" => "certificate") }

        describe "with `ssl_truststore_path` and `ssl_certificate_authorities`" do
          let(:config) { super().merge("ssl_truststore_path" => paths[:test_path], "ssl_certificate_authorities" => [paths[:test_path]]) }
          it "doesn't allow to use together" do
            expected_message = "`ssl_truststore_path` and `ssl_certificate_authorities` cannot be used together."
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "without `ssl_truststore_path` and empty `ssl_certificate_authorities`" do
          let(:config) { super().merge("ssl_certificate_authorities" => []) }
          it "doesn't allow empty `ssl_certificate_authorities`" do
            expected_message = "`ssl_certificate_authorities` cannot be empty"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "with `ssl_truststore_path`" do
          let(:config) { super().merge("ssl_truststore_path" => paths[:test_path]) }

          it "requires `ssl_truststore_password`" do
            expected_message = "`ssl_truststore_path` requires `ssl_truststore_password`"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end

          describe "with empty `ssl_truststore_password`" do
            let(:config) { super().merge("ssl_truststore_password" => "") }

            it "doesn't accept empty `ssl_truststore_password`" do
              allow(File).to receive(:writable?).and_return(false)
              expected_message = "`ssl_truststore_password` cannot be empty"
              expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end

          describe "with non-empty `ssl_truststore_password`" do
            let(:config) { super().merge("ssl_truststore_password" => "truststore_pa$$word") }

            describe "with non readable path" do
              before(:each) do
                allow(File).to receive(:readable?).and_return(false)
              end
              it "requires readable path" do
                expected_message = "Specified ssl_truststore_path #{paths[:test_path]} path must be readable."
                expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
              end
            end

            describe "with readable and writable path" do
              before(:each) do
                allow(File).to receive(:readable?).and_return(true)
                allow(File).to receive(:writable?).and_return(true)
              end
              it "requires non-writable path" do
                expected_message = "Specified ssl_truststore_path #{paths[:test_path]} path must not be writable."
                expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
              end
            end
          end
        end

        describe "with `ssl_truststore_password`" do
          let(:config) { super().merge("ssl_truststore_password" => "truststore_pa$$word") }

          it "requires `ssl_truststore_path`" do
            expected_message = "`ssl_truststore_password` is not allowed unless `ssl_truststore_path` is specified"
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
              expected_message = "Specified ssl_certificate_authorities #{paths[:test_path]} path must be readable."
              expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end

          describe "with readable and writable path" do
            before(:each) do
              allow(File).to receive(:readable?).and_return(true)
              allow(File).to receive(:writable?).and_return(true)
            end
            it "requires non-writable path" do
              expected_message = "Specified ssl_certificate_authorities #{paths[:test_path]} path must not be writable."
              expect { registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end
        end
      end

      context "connection prerequisites" do

        describe "with both `hosts` and `cloud_id`" do
          let(:config) {super().merge("hosts" => ["https://my-es-host:9200"], "cloud_id" => "foobar:dXMtZWFzdC0xLmF3cy5mb3VuZC5pbyRub3RhcmVhbCRpZGVudGlmaWVy")}

          it "raises an error" do
            expected_message = "`hosts` and `cloud_id` cannot be used together."
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "with `hosts`" do
          # since this is nested in a block that provides cloud_id
          # we need to delete it before adding the mutually-incompatible hosts.
          let(:config) { super().tap {|c| c.delete('cloud_id') } }

          describe "with HTTP scheme" do
            let(:config) { super().merge("hosts" => ["htt://my-es-cluster:1111", "https://cloud-test.es.us-west-2.aws.found.io"])}

            it "enforces to agree with scheme" do
              expected_message = "All hosts must agree with https schema when using `ssl_enabled`."
              expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)

            end
          end

          describe "with HTTPS scheme" do
            let(:config) { super().merge("hosts" => ["https://my-es-cluster:1111", "https://my-another-es-cluster:2222", "https://cloud-test.es.us-west-2.aws.found.io"])}

            it "accepts" do
              expect{ registered_plugin }.not_to raise_error
            end
          end

          describe "with multiple hosts" do
            let(:config) { super().merge("hosts" => ["my-es-cluster.com", "127.0.0.1:9200", "https://127.0.0.2", "https://127.0.0.3:9300", "https://127.0.0.3:9200/"]) }
            it "applies default value" do
              # makes sure the list in-order traverse
              expect(registered_plugin.hosts[0].eql?(::LogStash::Util::SafeURI.new("https://my-es-cluster.com:9200/"))).to be_truthy
              expect(registered_plugin.hosts[1].eql?(::LogStash::Util::SafeURI.new("https://127.0.0.1:9200/"))).to be_truthy
              expect(registered_plugin.hosts[2].eql?(::LogStash::Util::SafeURI.new("https://127.0.0.2:9200/"))).to be_truthy
              expect(registered_plugin.hosts[3].eql?(::LogStash::Util::SafeURI.new("https://127.0.0.3:9300/"))).to be_truthy
              expect(registered_plugin.hosts[4].eql?(::LogStash::Util::SafeURI.new("https://127.0.0.3:9200/"))).to be_truthy
            end
          end
        end
      end
    end

    context "with SSL disabled" do
      let(:config) { super().merge("ssl_enabled" => false) }

      context "with otherwise minimal config" do
        let(:config) { super().merge("hosts" => "localhost") }
        it 'does not have a value for meaningless ssl-related settings' do
          expect(registered_plugin).to have_attributes(ssl_verification_mode: nil,
                                                       ssl_truststore_path: nil,
                                                       ssl_truststore_password: nil,
                                                       ssl_certificate_authorities: nil,
                                                       ssl_keystore_path: nil,
                                                       ssl_keystore_password: nil,
                                                       ssl_certificate: nil,
                                                       ssl_key: nil,
                                                       ssl_key_passphrase: nil)
        end
      end

      context "with SSL related configs" do
        let(:config) { super().merge("ssl_keystore_path" => paths[:test_path], "ssl_certificate_authorities" => [paths[:test_path]], "hosts" => "127.0.0.1") }

        it "does not allow and raises an error" do
          expected_message = 'When SSL is disabled, the following provided parameters are not allowed: ["ssl_keystore_path", "ssl_certificate_authorities"]'
          expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
        end
      end

      context "with `cloud_id`" do
        let(:config) { super().merge("cloud_id" => "my-es-cloud:id_") }

        it "raises an error" do
          expected_message = 'When SSL is disabled, the following provided parameters are not allowed: ["cloud_id"]'
          expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
        end
      end

      describe "connection prerequisites" do

        context "with no `hosts` or `cloud_id" do
          it "requires either of them" do
            expected_message = "Either `hosts` or `cloud_id` is required"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        context "with either `hosts` or `cloud_id" do
          let(:config) { super().merge("hosts" => %w[http://my-es-cluster:1111 http://cloud-test.es.us-west-2.aws.found.io]) }

          it "accepts" do
            expect{ registered_plugin }.not_to raise_error
          end
        end

        context "with multiple auth options" do
          let(:config) {super().merge("cloud_auth" => "my_cloud_auth", "api_key" => "api_key", "hosts" => ["http://my-es-cluster:1111"])}

          it "does not allow" do
            expected_message = 'Multiple authentication ["cloud_auth", "api_key"] options cannot be used together. Please provide ONLY one.'
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        context "with empty auth option" do
          let(:config) { super().merge("cloud_auth" => "", "hosts" => "my-es-cluster.com:1111") }

          it "does not allow" do
            expected_message = "Empty `cloud_auth` is not allowed"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "`hosts`" do
          let(:config) { super().merge("hosts" => hosts) }

          context "with HTTP scheme" do
            let(:hosts) { %w[http://my-es-cluster:1111 http://cloud-test.es.us-west-2.aws.found.io] }

            it "accepts" do
              expect{ registered_plugin }.not_to raise_error
            end
          end

          context "with HTTPS scheme" do
            let(:hosts) { %w[http://my-es-cluster:1111 https://my-another-es-cluster:2222 https://cloud-test.es.us-west-2.aws.found.io] }

            it "enforces to agree with scheme" do
              expected_message = "All hosts must agree with http schema when NOT using `ssl_enabled`."
              expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end

          describe "single" do
            let(:hosts) { "my-es-cluster.com" }
            it "applies default values" do
              expect(registered_plugin.hosts.include?(::LogStash::Util::SafeURI.new("http://my-es-cluster.com:9200/"))).to be_truthy
            end
          end

          describe "with multiple hosts" do
            let(:hosts) { ["http://my-es-cluster.com", "127.0.0.1:9200", "http://127.0.0.2", "http://127.0.0.3:9300", "http://127.0.0.3:9200/"] }
            it "applies default value" do
              # makes sure the list in-order traverse
              expect(registered_plugin.hosts[0].eql?(::LogStash::Util::SafeURI.new("http://my-es-cluster.com:9200/"))).to be_truthy
              expect(registered_plugin.hosts[1].eql?(::LogStash::Util::SafeURI.new("http://127.0.0.1:9200/"))).to be_truthy
              expect(registered_plugin.hosts[2].eql?(::LogStash::Util::SafeURI.new("http://127.0.0.2:9200/"))).to be_truthy
              expect(registered_plugin.hosts[3].eql?(::LogStash::Util::SafeURI.new("http://127.0.0.3:9300/"))).to be_truthy
              expect(registered_plugin.hosts[4].eql?(::LogStash::Util::SafeURI.new("http://127.0.0.3:9200/"))).to be_truthy
            end
          end
        end

        describe "basic auth" do
          let(:config) { super().merge("hosts" => "my-es-cluster.com") }

          context "with `username`" do
            let(:config) { super().merge("username" => "test_user") }

            it "requires `password`" do
              expected_message = "`username` requires `password`"
              expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end

          context "with `password`" do
            let(:config) { super().merge("password" => "pa$$") }

            it "requires `username`" do
              expected_message = "`password` is not allowed unless `username` is specified"
              expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end

          context "when `username` is an empty" do
            let(:config) { super().merge("username" => "", "password" => "p$d") }

            it "requires non-empty `username`" do
              expected_message = "Empty `username` or `password` is not allowed"
              expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end

          context "when `password` is an empty" do
            let(:config) { super().merge("username" => "test_user", "password" => "") }

            it "requires non-empty `username`" do
              expected_message = "Empty `username` or `password` is not allowed"
              expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end
        end
      end
    end

    describe "plugin vs connected ES versions compatibility" do
      let(:config) { super().merge("hosts" => %w[127.0.0.2:9300]) }
      let(:version) { LogStash::Filters::ElasticIntegration::VERSION }
      let(:plugin_major_version) { version.split('.').first.to_i }
      let(:plugin_minor_version) { version.split('.')[1].to_i }
      let(:base_message) { "This #{version} version of plugin embedded Ingest node components from Elasticsearch #{plugin_major_version}.#{plugin_minor_version}" }
      let(:expected_message) { }

      before(:each) { plugin.tap(&:check_versions_alignment) }

      it "informs which version of ES the plugin is built from" do
        expected_message =
          "This plugin v#{version} is connected to the same MAJOR/MINOR version " +
            "of Elasticsearch v#{connected_es_version_info['number']}.\n"

        expect(mock_logger).to have_received(:info).with(base_message)
        expect(mock_logger).to have_received(:debug).with(expected_message)
      end

      shared_examples "version mismatch" do |version_type, ahead_or_behind, first_log_level, second_log_level|
        let(:connected_es_version_info) do
          major = plugin_major_version
          minor = plugin_minor_version
          major += ahead_or_behind == :ahead ? -1 : 1 if version_type == :major
          minor += ahead_or_behind == :ahead ? -1 : 1 if version_type == :minor
          { "number" => "#{major}.#{minor}.10", "major" => "#{major}", "minor" => "#{minor}", "build_flavor" => "default" }
        end

        it "informs to update" do
          expect(mock_logger).to have_received(first_log_level).with(base_message)
          expect(mock_logger).to have_received(second_log_level).with(expected_message)
        end
      end

      context "plugin major version is behind" do
        let(:expected_message) {
          "This plugin v#{version} is connected to a newer MAJOR version of " +
            "Elasticsearch v#{connected_es_version_info['number']}, and may have trouble loading or " +
            "running pipelines that use new features; for the best experience, " +
            "update this plugin to at least v#{connected_es_version_info['major']}.#{connected_es_version_info['minor']}\n"
        }
        include_examples "version mismatch", :major, :behind, :info, :warn
      end

      context "plugin major version is ahead" do
        let(:expected_message) {
          "This plugin v#{version} is connected to an older MAJOR version of " +
            "Elasticsearch v#{connected_es_version_info['number']}, and may have trouble loading or " +
            "running pipelines that use features that were deprecated before " +
            "Elasticsearch v#{plugin_major_version}.0; for the best experience, " +
            "align major/minor versions across the Elastic Stack.\n"
        }
        include_examples "version mismatch", :major, :ahead, :info, :warn
      end

      context "plugin minor version is behind" do
        let(:expected_message) {
          "This plugin v#{version} is connected to a newer MINOR version of " +
            "Elasticsearch v#{connected_es_version_info['number']}, and may have trouble loading or " +
            "running pipelines that use new features; for the best experience, " +
            "update this plugin to at least v#{connected_es_version_info['major']}.#{connected_es_version_info['minor']}\n"
        }
        include_examples "version mismatch", :minor, :behind, :info, :warn
      end

      context "plugin minor version is ahead" do
        let(:expected_message) {
          "This plugin v#{version} is connected to an older MINOR version of " +
            "Elasticsearch v#{connected_es_version_info['number']}; for the best experience, " +
            "align major/minor versions across the Elastic Stack.\n"
        }
        include_examples "version mismatch", :minor, :ahead, :info, :info
      end

    end
  end
end