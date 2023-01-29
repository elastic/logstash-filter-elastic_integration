require "logstash/devutils/rspec/spec_helper"
require "logstash/devutils/rspec/shared_examples"

require "logstash/filters/elastic_integration"

describe LogStash::Filters::ElasticIntegration do
  let(:config) {{ }}
  let(:paths) do
    {
      # paths have to be created, otherwise config :path validation fails
      # and since we cannot control the chmod operations on paths, we should stub file readable? and writable? operations
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

    shared_examples "raises an error" do |expected_message:|
      it "raises an error" do
        expect{ registered_plugin }.to raise_error(LogStash::ConfigurationError).with_message(instance_exec(&expected_message))
      end
    end

    describe "with SSL enabled" do
      let(:config) { super().merge("ssl_verification_mode" => "none") }

      include_examples "raises an error",
                       expected_message: -> { "SSL requires either `ssl_certificate` or `keystore` to be set." }

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

      describe "with `keystore`" do
        let(:config) { super().merge("keystore" => paths[:readable])}

        describe "without `truststore_password`" do
          let(:config) { super().merge("keystore" => paths[:readable]) }

          include_examples "raises an error",
                           expected_message: -> { "Using `keystore` requires `keystore_password`" }
        end

        context "load from path" do

          let(:config) { super().merge("keystore_password" => "keystore_pa$sword") }

          describe "with non-readable path" do
            before(:each) do
              allow(File).to receive(:readable?).and_return(false)
            end
            include_examples "non-readable path", path_name: "keystore",
                             path: -> {paths[:non_readable]},
                             expected_message: -> {"Key(s) from the #{paths[:non_readable]} path cannot be loaded. Please make the path readable."}
          end

          describe "with writable path" do
            before(:each) do
              allow(File).to receive(:writable).and_return(true)
            end
            include_examples "writable path", path_name: "keystore",
                             path: -> {paths[:writable]},
                             expected_message: -> {"Specified keystore #{paths[:writable]} path cannot be writable for security reasons."}
          end
        end
      end

      describe "with `ssl_certificate`" do
        let(:config) { super().merge("ssl_certificate" => paths[:readable])}

        describe "with non-readable path" do

          before(:each) do
            allow(File).to receive(:readable?).and_return(false)
          end
          include_examples "non-readable path", path_name: "ssl_certificate",
                           path: -> {paths[:non_readable]},
                           expected_message: -> {"SSL certificate from the #{paths[:non_readable]} path cannot be loaded. Please make the path readable."}
        end

        describe "with writable path" do
          before(:each) do
            allow(File).to receive(:writable?).and_return(true)
          end
          include_examples "writable path", path_name: "ssl_certificate",
                           path: -> {paths[:writable]},
                           expected_message: -> {"Specified SSL certificate #{paths[:writable]} path cannot be writable for security reasons."}
        end

        describe "with `ssl_key`" do

          describe "without `ssl_key_passphrase`" do
            let(:config) { super().merge("ssl_key" => paths[:readable]) }

            before(:each) do
              allow(File).to receive(:readable?).and_return(true)
              allow(File).to receive(:writable?).and_return(false)
            end

            include_examples "raises an error",
                             expected_message: -> {"Using `ssl_key` requires `ssl_key_passphrase`"}
          end

          context "load from path" do
            let(:config) { super().merge("ssl_key_passphrase" => "ssl_key_pa$$phrase") }

            describe "with non-readable path" do
              before(:each) do
                allow(File).to receive(:readable?).and_return(true, false)
                allow(File).to receive(:writable?).and_return(false)
              end
              include_examples "non-readable path", path_name: "ssl_key",
                               path: -> {paths[:non_readable]},
                               expected_message: -> {"SSL key cannot be loaded from the specified #{paths[:non_readable]} path. Please make the path readable."}
            end

            describe "with writable path" do
              before(:each) do
                allow(File).to receive(:readable).and_return(true)
                allow(File).to receive(:writable?).and_return(false, true)
              end
              include_examples "writable path", path_name: "ssl_key",
                               path: -> {paths[:writable]},
                               expected_message: -> {"Specified SSL key #{paths[:writable]} path cannot be writable for security reasons."}
            end
          end
        end
      end

      describe "with `ssl_verification_mode` (not `none`)" do
        let(:config) { super().merge("ssl_verification_mode" => "certificate")}

        include_examples "raises an error",
                         expected_message: -> {"`ssl_verification_mode` certificate requires either `truststore` or `ssl_certificate_authorities` to be set."}

        describe "with `truststore`" do
          let(:config) { super().merge("truststore" => paths[:readable]) }

          describe "without `truststore_password`" do
            include_examples "raises an error",
                             expected_message: -> {"Using `truststore` requires `truststore_password`"}
          end

          context "load from path" do
            let(:config) { super().merge("truststore_password" => "truststore_pa$sword") }

            describe "with non-readable path" do
              before(:each) do
                allow(File).to receive(:readable?).and_return(false)
              end
              include_examples "non-readable path", path_name: "truststore",
                               path: -> {paths[:non_readable]},
                               expected_message: -> {"SSL credentials cannot be loaded from the specified #{paths[:non_readable]} path. Please make the path readable."}
            end

            describe "with writable path" do
              before(:each) do
                allow(File).to receive(:writable?).and_return(true)
              end
              include_examples "writable path", path_name: "truststore",
                               path: -> {paths[:writable]},
                               expected_message: -> {"Specified truststore #{paths[:writable]} cannot be writable for security reasons."}
            end
          end
        end

        describe "with `ssl_certificate_authorities`" do

          context "load from path" do

            describe "with non-readable path" do
              let(:config) { super().merge("ssl_certificate_authorities" => [paths[:non_readable]]) }
              before(:each) do
                allow(File).to receive(:readable?).and_return(false)
              end
              include_examples "non-readable path", path_name: "ssl_certificate_authorities",
                               path: -> {paths[:non_readable]},
                               expected_message: -> { "Certificate authority cannot be loaded from the specified #{paths[:non_readable]} path. Please make the path readable." }
            end

            describe "with writable path" do
              let(:config) { super().merge("ssl_certificate_authorities" => [paths[:writable]]) }
              before(:each) do
                allow(File).to receive(:writable?).and_return(true)
              end
              include_examples "writable path", path_name: "ssl_certificate_authorities",
                               path: -> {paths[:writable]},
                               expected_message: -> {"Specified certificate authority #{paths[:writable]} cannot be writable for security reasons."}
            end
          end
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
        let(:config) { super().merge("hosts" => ["http://my-es-cluster:1111", "https://my-another-es-cluster:2222", "https://cloud-test.es.us-west-2.aws.found.io"], "ssl_verification_mode" => "none") }

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
          let(:config) { super().merge(config.merge({"auth_basic_username" => "uname", "auth_basic_password" => "test_user_p@s$code", "hosts" => ["http://my-es.com"]})) }

          it "warns about security risk when sending credentials" do
            expected_message = "Credentials are being sent over unencrypted HTTP. This may bring security risk."
            allow(plugin.logger).to receive(:warn).with(any_args)
            expect(registered_plugin.logger).to have_received(:warn).with(expected_message)
          end
        end
      end
    end

    context "connection prerequisites" do
      let(:config) { super().merge("ssl" => false, "ssl_verification_mode" => "none") }

      describe "with `hosts` and `cloud_id`" do

        describe "when no option specified" do
          include_examples "raises an error",
                           expected_message: -> {"Connection to Elasticsearch requires either `hosts` or `cloud_id` to be set."}
        end

        describe "when multiple options are specified" do
          let(:config) {super().merge("hosts" => ["http://my-es-host:9200"], "cloud_id" => "my_cloud_id")}

          include_examples "raises an error",
                           expected_message: -> {"Connection to Elasticsearch requires either `hosts` or `cloud_id` to be set."}
        end

      end

      describe "with [`auth_basic_username`, `cloud_auth`, `api_key`] auth options" do

        describe "when no option specified" do
          let(:config) {super().merge("cloud_id" => "my_cloud_id")}

          include_examples "raises an error",
                           expected_message: -> {"Authentication to Elasticsearch requires ONLY ONE of [`auth_basic_username`, `cloud_auth`, `api_key`] options to be set."}
        end

        describe "when multiple options are specified" do
          let(:config) {super().merge("cloud_id" => "my_cloud_id", "cloud_auth" => "my_cloud_auth", "api_key" => "api_key")}

          include_examples "raises an error",
                           expected_message: -> {"Authentication to Elasticsearch requires ONLY ONE of [`auth_basic_username`, `cloud_auth`, `api_key`] options to be set."}
        end

      end

      describe "with hosts" do
        let(:config) { super().merge("ssl_verification_mode" =>"none", "hosts" => ["http://my-es-cluster:1111", "https://my-another-es-cluster:2222", "https://cloud-test.es.us-west-2.aws.found.io"])}

        before(:each) do
          allow(File).to receive(:readable?).and_return(true)
          allow(File).to receive(:writable?).and_return(false )
        end

        include_examples "raises an error",
                         expected_message: -> {"All hosts must agree with http schema when NOT using `ssl`."}

        describe "with SSL" do
          let(:config) { super().merge("ssl" => true, "keystore" => paths[:readable], "keystore_password" => "keystore_pa$$word")}

          include_examples "raises an error",
                           expected_message: -> {"All hosts must agree with https schema when  using `ssl`."}
        end
      end

      describe "with basic auth" do
        let(:config) { super().merge("ssl_verification_mode" =>"none", "auth_basic_username" => "test_user", "truststore" => paths[:readable], "truststore_password" => "truststore_pa$$word")}

        before(:each) do
          allow(File).to receive(:readable?).and_return(true)
          allow(File).to receive(:writable?).and_return(false )
        end

        include_examples "raises an error",
                         expected_message: -> {"Using `auth_basic_username` requires `auth_basic_password`"}
      end
    end
  end
end