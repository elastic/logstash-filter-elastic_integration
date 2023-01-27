require "logstash/devutils/rspec/spec_helper"
require "logstash/devutils/rspec/shared_examples"

require "logstash/filters/elastic_integration"

describe LogStash::Filters::ElasticIntegration do
  let(:config) {{ }}
  let(:paths) do
    {
      :readable => "spec/filters/resources/readable_path",
      :non_readable => "spec/filters/resources/non_readable_path",
      :writable => "spec/filters/resources/writable_path",
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

    describe "with SSL enabled" do

      shared_examples "non-readable path" do |path_name:, path:, expected_message:|
        # path & expected_message are dynamic [Proc], make sure to get the value by executing with instance_exec(&)
        let(:config) { super().merge(path_name => instance_exec(&path)) }
        it "raises cannot read from path error" do
          expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(instance_exec(&expected_message))
        end
      end

      shared_examples "writable path" do |path_name:, path:, expected_message:|
        # path & expected_message are dynamic[Proc], make sure to get the value by executing with instance_exec(&)
        let(:config) { super().merge(path_name => instance_exec(&path)) }
        it "raises a security risk error since path is writable" do
          expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(instance_exec(&expected_message))
        end
      end

      describe "with `ssl_verification_mode` (not `none`)" do
        let(:config) { super().merge("ssl_verification_mode" => "certificate")}

        describe "with `truststore`" do

          describe "without `truststore_password`" do
            let(:config) { super().merge("truststore" => paths[:readable]) }

            it "throws an error when using `truststore`" do
              expected_message = "Using `truststore` requires `truststore_password`"
              expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end

          context "load from path" do
            let(:config) { super().merge("truststore_password" => "truststore_pa$sword") }

            describe "with non-readable path" do
              include_examples "non-readable path", path_name: "truststore",
                               path: -> {paths[:non_readable]},
                               expected_message: -> {"SSL credentials cannot be loaded from the specified #{paths[:non_readable]} path. Please make the path readable."}
            end
            describe "with writable path" do
              include_examples "writable path", path_name: "truststore",
                               path: -> {paths[:writable]},
                               expected_message: -> {"Specified truststore #{paths[:writable]} cannot be writable for security reasons."}
            end
          end
        end

        describe "with SSL certificate" do
          let(:config) { super().merge("ssl_certificate" => "spec/filters/resources/readable_path") }

          describe "with non-readable path" do
            include_examples "non-readable path", path_name: "ssl_certificate",
                             path: -> {paths[:non_readable]},
                             expected_message: -> {"SSL certificate from the #{paths[:non_readable]} path cannot be loaded. Please make the path readable."}
          end

          describe "with writable path" do
            include_examples "writable path", path_name: "ssl_certificate",
                             path: -> {paths[:writable]},
                             expected_message: -> {"Specified SSL certificate #{paths[:writable]} path cannot be writable for security reasons."}
          end

          describe "with `ssl_key`" do
            describe "without `ssl_key_passphrase`" do
              let(:config) { super().merge("ssl_key" => "spec/filters/resources/readable_path") }

              it "throws key phrase required error" do
                expected_message = "Using `ssl_key` requires `ssl_key_passphrase`"
                expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
              end
            end

            context "load from path" do
              let(:config) { super().merge("ssl_key_passphrase" => "ssl_key_pa$$phrase") }

              describe "with non-readable path" do
                include_examples "non-readable path", path_name: "ssl_key",
                                 path: -> {paths[:non_readable]},
                                 expected_message: -> {"SSL key cannot be loaded from the specified #{paths[:non_readable]} path. Please make the path readable."}
              end
              describe "with writable path" do
                include_examples "writable path", path_name: "ssl_key",
                                 path: -> {paths[:writable]},
                                 expected_message: -> {"Specified SSL key #{paths[:writable]} path cannot be writable for security reasons."}
              end
            end
          end
        end

        describe "with keystore" do
          describe "without `truststore_password`" do
            let(:config) { super().merge("keystore" => "spec/filters/resources/readable_path") }

            it "throws an error when using `keystore`" do
              expected_message = "Using `keystore` requires `keystore_password`"
              expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
            end
          end

          context "load from path" do
            let(:config) { super().merge("keystore_password" => "keystore_pa$sword") }

            describe "with non-readable path" do
              include_examples "non-readable path", path_name: "keystore",
                               path: -> {paths[:non_readable]},
                               expected_message: -> {"Key(s) from the #{paths[:non_readable]} path cannot be loaded. Please make the path readable."}
            end
            describe "with writable path" do
              include_examples "writable path", path_name: "keystore",
                               path: -> {paths[:writable]},
                               expected_message: -> {"Specified keystore #{paths[:writable]} path cannot be writable for security reasons."}
            end
          end
        end
      end

      describe "with hosts" do
        let(:config) { super().merge("hosts" => [["http://my-es-cluster:1111", "https://my-another-es-cluster:2222", "https://cloud-test.es.us-west-2.aws.found.io"]])}

        it "throws an error if any host of hosts is not HTTPS" do
          expected_message = "All hosts must agree with https schema when  using `ssl`."
          expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
        end
      end

      describe "with basic auth" do
        let(:config) { super().merge("auth_basic_username" => "test_user")}

        it "throws an error if `auth_basic_username` is specified but `auth_basic_password` isn't provided." do
          expected_message = "Using `auth_basic_username` requires `auth_basic_password`"
          expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
        end
      end
    end

    describe "with SSL disabled" do
      let(:config) {{ "ssl" => false }}

      it "throws an error if `ssl_verification_mode` is `full` or `certificate`" do
        expected_message = "Using `ssl_verification_mode` full requires `ssl` enabled"
        expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
      end

      describe "with hosts" do
        let(:config) { super().merge("hosts" => [["http://my-es-cluster:1111", "https://my-another-es-cluster:2222", "https://cloud-test.es.us-west-2.aws.found.io"]], "ssl_verification_mode" => "none") }

        it "throws an error if any host of hosts is not HTTP" do
          expected_message = "All hosts must agree with http schema when NOT using `ssl`."
          expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
        end
      end

      describe "with basic auth" do
        let(:config) {super().merge({"auth_basic_username" => "test_user", "ssl_verification_mode" => "none"})}

        describe "without password" do
          it "throws an error if `auth_basic_username` is specified but `auth_basic_password` isn't provided." do
            expected_message = "Using `auth_basic_username` requires `auth_basic_password`"
            expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)
          end
        end

        describe "with password" do
          let(:config) { super().merge(config.merge({"auth_basic_password" => "test_user_p@s$code"})) }

          it "warns about security risk when sending credentials" do
            expected_message = "Credentials are being sent over unencrypted HTTP. This may bring security risk."
            allow(plugin.logger).to receive(:warn).with(any_args)
            expect(registered_plugin.logger).to have_received(:warn).with(expected_message)
          end
        end
      end
    end
  end
end