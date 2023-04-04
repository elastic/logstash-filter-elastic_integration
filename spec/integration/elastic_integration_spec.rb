require_relative "../../spec/spec_helper"
require 'manticore'
require 'logstash/filters/elastic_integration'

## This plugin uses /_security/user/_has_privileges API which
# requires xpack enabled & SSL enabled
describe 'Logstash executes ingest pipeline', :secure_integration => true do

  let(:es_http_client_options) {
    {
      ssl: {
        ca_file: 'spec/fixtures/test_certs/root.crt',
        verify: :none
      }
    }
  }
  let(:es_http_client) { Manticore::Client.new(es_http_client_options) }

  let(:integ_user_name) { "admin" }
  let(:integ_user_password) { "elastic" }
  let(:settings) {
    {
      "hosts" => "https://elasticsearch:9200",
      "auth_basic_username" => integ_user_name,
      "auth_basic_password" => integ_user_password,
      "ssl_enabled" => true,
      "ssl_verification_mode" => "certificate",
      "ssl_certificate_authorities" => "spec/fixtures/test_certs/root.crt",
      "ssl_certificate" => "spec/fixtures/test_certs/client_from_root.crt",
      "ssl_key" => "spec/fixtures/test_certs/client_from_root.key.pkcs8",
      "ssl_key_passphrase" => "12345678"
    }
  }

  let(:index_settings) {
    {
      "type" => "integration",
      "dataset" => "logstash_test.events",
      "namespace" => "default"
    }
  }

  let(:pipeline_setting) {
    # integration-logstash_test.events-default
    index_settings["type"] + "-" + index_settings["dataset"] + "-" + index_settings["namespace"]
  }

  let(:data_stream) {
    {
      "type" => index_settings["type"],
      "dataset" => index_settings["dataset"],
      "namespace" => index_settings["namespace"]
    }
  }

  let(:pipeline_processors_setting) {
    '{
      "processors" : [
        ' + pipeline_processor + '
      ]
    }'
  }
  let(:pipeline_processor) {}

  let(:index_pattern) {
    "logs-logstash-*"
  }
  let(:index_template_setting) { '
    {
      "index_patterns": ["' + index_pattern + '"],
      "data_stream": { },
      "template": {
        "settings": {
          "index.default_pipeline": "' + pipeline_setting + '-pipeline",
          "index.lifecycle.name": "logs"
        }
      }
    }'
  }

  subject(:elastic_integration_plugin) { LogStash::Filters::ElasticIntegration.new(settings) }

  before(:each) do
    # create an ingest pipeline
    ingest_pipeline_url = get_host_port_for_es_client + "/_ingest/pipeline/" + pipeline_setting + "-pipeline"
    es_http_client.put(ingest_pipeline_url, :body => pipeline_processors_setting, :headers => { "Content-Type" => "application/json" }).call

    # create an index template by setting default pipeline
    index_template_url = get_host_port_for_es_client + "/_index_template/" + pipeline_setting
    es_http_client.put(index_template_url, :body => index_template_setting, :headers => { "Content-Type" => "application/json" }).call

  end

  after(:each) do
    # remove index template
    index_template_url = get_host_port_for_es_client + "/_index_template/" + pipeline_setting
    es_http_client.delete(index_template_url).call

    # remove ingest pipeline
    ingest_pipeline_url = get_host_port_for_es_client + "/_ingest/pipeline/" + pipeline_setting + "-pipeline"
    es_http_client.delete(ingest_pipeline_url).call

    subject.close
  end

  context '#pipeline execution' do

    before(:each) do
      subject.register
    end

    describe 'with append processor' do
      let(:pipeline_processor) {
        '{
          "append": {
            "field": "append_field",
            "value": ["integration", "test"]
          }
        }'
      }

      it 'appends values to the field' do
        expected_append_field = ["Append to me.","integration","test"]
        events = [LogStash::Event.new(
          "message" => "'integration' and 'test' are to append to [append_field] field.",
          "append_field" => "Append to me.",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("append_field")).to eql expected_append_field
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with bytes processor' do
      let(:pipeline_processor) {
        '{
          "bytes": {
            "field": "size"
          }
        }'
      }

      it 'counts message size into bytes' do
        events = [LogStash::Event.new(
          "message" => "[size] 2kb field will be 2048 byte.",
          "size" => "2kb",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("size")).to eql 2048
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with community-id processor' do
      let(:pipeline_processor) {
        '{
          "community_id": {
          }
        }'
      }

      it 'computes the Community ID for network flow data' do
        events = [LogStash::Event.new(
          "message" => "Calculates the community_id.",
          "source" => { "ip" => "123.124.125.126", "port" => 12345},
          "destination" => { "ip" => "55.56.57.58", "port" => 80 },
          "network" => { "transport" => "TCP"},
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("[network][community_id]").nil?).to be_falsey
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with convert processor' do
      let(:pipeline_processor) {
        '{
          "convert" : {
            "field" : "id",
            "type": "integer"
          }
        }'
      }

      it 'converts field type' do
        events = [LogStash::Event.new(
          "message" => "[id] field is going to be converted into integer.",
          "id" => "200",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("id")).to eql 200
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with csv processor' do
      let(:pipeline_processor) {
        '{
          "csv": {
            "field": "csv_field",
            "target_fields": ["csv_name_parsed", "csv_email_parsed", "csv_phone_parsed", "csv_address_parsed"]
          }
        }'
      }

      it 'parses field from CSV' do
        events = [LogStash::Event.new(
          "message" => "Extracts CSV data from [csv_field] field to [target_fields].",
          "csv_field" => "Elephant,elephant@example.com,111-222-3344,Elephant's address.",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("csv_name_parsed")).to eql "Elephant"
          expect(event.get("csv_email_parsed")).to eql "elephant@example.com"
          expect(event.get("csv_phone_parsed")).to eql "111-222-3344"
          expect(event.get("csv_address_parsed")).to eql "Elephant's address."
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with date processor' do
      let(:pipeline_processor) {
        '{
          "date" : {
            "field" : "initial_date",
            "target_field" : "timestamp",
            "formats" : ["dd/MM/yyyy HH:mm:ss"],
            "timezone" : "Europe/Amsterdam"
          }
        }'
      }

      it 'parses date' do
        events = [LogStash::Event.new(
          "message" => "Parses the [initial_date] field into [timestamp] field as a date format.",
          "initial_date" => "08/03/2023 09:10:17",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("timestamp")).to eql "2023-03-08T09:10:17.000+01:00"
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with date-index-name processor' do
      let(:pipeline_processor) {
        '{
          "date_index_name" : {
            "field" : "date_field",
            "index_name_prefix" : "monthly-index-",
            "date_rounding" : "M"
          }
        }'
      }

      it 'prepares the route index based on provided field and prefix' do
        events = [LogStash::Event.new(
          "message" => "Prepares the route index based on provided field and prefix.",
          "date_field" => "2023-03-08T09:10:17.789Z",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("_index")).to include("<monthly-index-{2023-03-08")
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with dissect processor' do
      let(:pipeline_processor) {
        '{
          "dissect": {
            "field": "dissect_field",
            "pattern" : "%{clientip} %{ident} %{auth} [%{@timestamp}] \"%{verb} %{request} HTTP/%{httpversion}\" %{status} %{size}"
           }
        }'
      }

      it 'parses the field based on the pattern' do
        events = [LogStash::Event.new(
          "message" => "Parses Nginx single line log.",
          "dissect_field" => "1.2.3.4 - - [01/Apr/2023:22:00:52 +0000] \"GET /path/to/some/resources/test.gif HTTP/1.0\" 200 3171",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("clientip")).to eql "1.2.3.4"
          expect(event.get("ident")).to eql "-"
          expect(event.get("auth")).to eql "-"
          expect(event.get("@timestamp")).to eql "01/Apr/2023:22:00:52 +0000"
          expect(event.get("verb")).to eql "GET"
          expect(event.get("request")).to eql "/path/to/some/resources/test.gif"
          expect(event.get("httpversion")).to eql "1.0"
          expect(event.get("status")).to eql "200"
          expect(event.get("size")).to eql "3171"
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with dot-expander processor' do
      let(:pipeline_processor) {
        '{
          "dot_expander": {
            "field": "*"
          }
        }'
      }

      it 'expands the field with dots into object field' do
        events = [LogStash::Event.new(
          "message" => "Expands the field with dot and makes a deeper object.",
          "foo.bar" => "foo -> bar value",
          "parent.child" => "parent -> child value",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("[foo][bar]")).to eql "foo -> bar value"
          expect(event.get("[parent][child]")).to eql "parent -> child value"
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with drop processor' do
      let(:pipeline_processor) {
        '{
          "drop": {
            "if": "ctx.user_type == \'Guest\'"
          }
        }'
      }

      it 'drops an event (when condition satisfies)' do
        events = [LogStash::Event.new(
          "message" => "This event is going to be dropped.",
          "user_type" => "Guest",
          "data_stream" => data_stream),
                  LogStash::Event.new(
                    "message" => "This event is NOT going to be dropped.",
                    "user_type" => "Authorized",
                    "data_stream" => data_stream)
        ]

        processed_events = subject.multi_filter(events)
        # dropping doesn't me to remove the event
        # we still keep it and mark it as cancelled
        expect(processed_events.size).to eql 2
        processed_events.each do |event|
          if event.get("user_type") == "Guest"
            expect(event.cancelled?).to be_truthy
          end
        end
      end

    end

    describe 'with fingerprint processor' do
      let(:pipeline_processor) {
        '{
          "fingerprint": {
            "fields": ["animal"]
          }
        }'
      }

      it 'calculates the has value of provided field' do
        events = [LogStash::Event.new(
          "message" => "Expands the field with dot and makes a deeper object.",
          "animal" => { "name" => "piggy", "age" => 2, "color" => "pink"},
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("fingerprint")).to eql "XqSwreW5FVPwjCF9pB7tzX6fQBs="
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with foreach processor' do
      let(:pipeline_processor) {
        '{
          "foreach" : {
            "field" : "values",
            "processor" : {
              "uppercase" : {
                "field" : "_ingest._value"
              }
            }
          }
        }'
      }

      it 'iterates over the collection and proceeds the defined processor' do
        events = [LogStash::Event.new(
          "message" => "Iterates over products array and uppercases display name.",
          "values" => ["foo", "bar", "baz"],
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("values")== ["FOO","BAR","BAZ"]).to be_truthy
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with grok pattern processor' do
      let(:pipeline_processor) {
        '{
          "grok": {
            "field": "message",
            "patterns": ["%{IP:client} %{WORD:method} %{URIPATHPARAM:request} %{NUMBER:bytes:int} %{NUMBER:duration:double}"]
          }
        }'
      }

      it 'parses values based on grok pattern' do
        events = [LogStash::Event.new(
          "message" => "55.3.244.1 GET /index.html 15824 0.043",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("client")).to eql "55.3.244.1"
          expect(event.get("method")).to eql "GET"
          expect(event.get("request")).to eql "/index.html"
          expect(event.get("bytes")).to eql 15824
          expect(event.get("duration")).to eql 0.043
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with gsub processor' do
      let(:pipeline_processor) {
        '{
          "gsub": {
            "field": "gsub_field",
            "pattern": "//.",
            "replacement": "-"
          }
        }'
      }

      it 'replaces the field matches with replacement based on the pattern' do
        events = [LogStash::Event.new(
          "message" => "All dots become hyphen",
          "gsub_field" => %W[kit//.ten dog//.gy elephant],
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("gsub_field") == %w[kit-ten dog-gy elephant]).to be_truthy
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with HTML strip processor' do
      let(:pipeline_processor) {
        '{
          "html_strip": {
            "field": "strip_field"
          }
        }'
      }

      it 'strips HTML format' do
        events = [LogStash::Event.new(
          "message" => "Each HTML tag in [strip_field] will be replaced with \n.",
          "strip_field" => "<h1> HTML </h1> <p> <em> <strong> fast, and brutal </strong> </em> </p>",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("strip_field") == "\n HTML \n \n   fast, and brutal   \n").to be_truthy
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with join processor' do
      let(:pipeline_processor) {
        '{
          "join": {
            "field": "joined_array_field",
            "separator": "-"
          }
        }'
      }

      it 'joins array elements into single string with a separator' do
        events = [LogStash::Event.new(
          "message" => "[joined_array_field] becomes 1-2-3-4.",
          "joined_array_field" => %w[1 2 3 4],
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("joined_array_field")).to eql "1-2-3-4"
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with json processor' do
      let(:pipeline_processor) {
        '{
          "json" : {
            "field" : "json_string",
            "target_field" : "json_target"
          }
        }'
      }

      it 'converts JSON string into JSON object' do
        events = [LogStash::Event.new(
          "message" => "[json_string] is a string, becomes s JSON object.",
          "json_string" => "{\"foo\": 2000}",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("[json_target][foo]")).to eql 2000
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with kv processor' do
      let(:pipeline_processor) {
        '{
          "kv": {
            "field": "message",
            "field_split": " ",
            "value_split": "="
          }
        }'
      }

      it 'parses the message into key=value format' do
        events = [LogStash::Event.new(
          "message" => "ip=1.2.3.4 error=REFUSED",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("ip")).to eql "1.2.3.4"
          expect(event.get("error")).to eql "REFUSED"
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with lowercase processor' do
      let(:pipeline_processor) {
        '{
          "lowercase": {
            "field": "cats"
          }
        }'
      }

      it 'lowercases the field' do
        expected_message = "elephant is dancing because it is raining like cats and dogs."
        events = [LogStash::Event.new(
          "message" => "[cats] field is going to be transformed into small chars.",
          "cats" => "ELEPHANT IS DANCING BECAUSE IT IS RAINING LIKE cats and dogs.",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("cats")).to eql expected_message
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with network direction processor' do
      let(:pipeline_processor) {
        '{
          "network_direction": {
            "internal_networks": ["private"]
          }
        }'
      }

      it 'calculates the network direction given a source IP address, destination IP address' do
        events = [LogStash::Event.new(
          "message" => "Inbound network.",
          "source" => { "ip" => "128.232.110.120" },
          "destination" => { "ip" => "192.168.1.1" },
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("[network][direction]")).to eql "inbound"
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with registered domain processor' do
      let(:pipeline_processor) {
        '{
          "registered_domain": {
            "field": "domain_field",
            "target_field": "url"
          }
        }'
      }

      it 'extracts registered domain' do
        events = [LogStash::Event.new(
          "message" => "Registered domain.",
          "domain_field" => "www.example.ac.uk",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("[url][top_level_domain]")).to eql"ac.uk"
          expect(event.get("[url][subdomain]")).to eql "www"
          expect(event.get("[url][registered_domain]")).to eql "example.ac.uk"
          expect(event.get("[url][domain]")).to eql "www.example.ac.uk"
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with remove processor' do
      let(:pipeline_processor) {
        '{
          "remove": {
            "field": "user_agent"
          }
        }'
      }

      it 'removes user agent field' do
        events = [LogStash::Event.new(
          "message" => "Let's remove [user_agent] field.",
          "user_agent" => "example agent field",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("user_agent").nil?).to be_truthy
        end
      end

      it 'throws an exception when field is not exist' do
        events = [LogStash::Event.new(
          "message" => "Let's remove [user_agent] field.",
          "non_exist_user_agent" => "example agent field",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("tags")).to include("_ingest_pipeline_failure")
        end
      end

    end

    describe 'with rename processor' do
      let(:pipeline_processor) {
        '{
          "rename": {
            "field": "rename_field_from",
            "target_field": "rename_field_to"
          }
        }'
      }

      it 'renames the field to target field' do
        events = [LogStash::Event.new(
          "message" => "[rename_field_from] becomes [rename_field_to].",
          "rename_field_from" => "I have to be renamed to rename_field_to.",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("rename_field_from")).to be_nil
          expect(event.get("rename_field_to").nil?).to be_falsey
          expect(event.get("rename_field_to")).to eql "I have to be renamed to rename_field_to."
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with script processor' do
      let(:pipeline_processor) {
        '{
          "script": {
            "lang": "painless",
            "source": "ctx[\'_index\'] = ctx[\'lang\'] + \'-\' + params[\'dataset\'];",
            "params": {
              "dataset": "catalog"
            }
          }
        }'
      }

      it 'runs painless script on a given field' do
        events = [LogStash::Event.new(
          "message" => "Should extract prod tag from env field",
          "lang" => "uz",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("_index")).to eql "uz-catalog"
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with set processor' do
      let(:pipeline_processor) {
        '{
          "set": {
            "field": "elephant_age",
            "value": 120
          }
        }'
      }

      it 'sets the field' do
        expected_value = 120
        events = [LogStash::Event.new(
          "message" => "[elephant_age] field is going to be set to 120.",
          "elephant_age" => 0,
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("elephant_age")).to eql expected_value
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with sort processor' do
      let(:pipeline_processor) {
        '{
          "sort": {
            "field": "array_field_to_sort",
            "order": "desc"
          }
        }'
      }

      it 'sorts the array of the event' do
        events = [LogStash::Event.new(
          "message" => "[array_field_to_sort] will be sorted in descending order.",
          "array_field_to_sort" => [1,3,8,2,4],
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("array_field_to_sort")).to eql [8,4,3,2,1]
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end
    end

    describe 'with split processor' do
      let(:pipeline_processor) {
        '{
          "split": {
            "field": "split_field",
            "separator": ","
          }
        }'
      }

      it 'splits the field based on separator' do
        events = [LogStash::Event.new(
          "message" => "[split_field] will be split into array.",
          "split_field" => "1,2,3,4,5,6,7,8",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("split_field")).to eql %w[1 2 3 4 5 6 7 8]
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with trim processor' do
      let(:pipeline_processor) {
        '{
          "trim": {
            "field": "trim_field"
          }
        }'
      }

      it 'trims the field' do
        events = [LogStash::Event.new(
          "message" => "Trim the [trim_field] field.",
          "trim_field" => " Trimming the field ",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("trim_field")).to eql "Trimming the field"
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with uppercase processor' do
      let(:pipeline_processor) {
        '{
          "uppercase": {
            "field": "dogs"
          }
        }'
      }

      it 'uppercases the field' do
        expected_message = "ELEPHANT IS DANCING BECAUSE IT IS RAINING LIKE CATS AND DOGS."
        events = [LogStash::Event.new(
          "message" => "[dogs] field is going to be transformed into capital chars.",
          "dogs" => "elephant is dancing because it is raining like cats and dogs.",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("dogs")).to eql expected_message
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with uri-parts processor' do
      let(:pipeline_processor) {
        '{
          "uri_parts": {
            "field": "uri_field",
            "target_field": "url",
            "keep_original": true,
            "remove_if_successful": false
          }
        }'
      }

      it 'parses URI parts' do
        events = [LogStash::Event.new(
          "message" => "Parses the URI field and sets into object.",
          "uri_field" => "http://myusername:mypassword@www.example.com:80/foo.gif?key1=val1&key2=val2#fragment",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("[url][path]")).to eql "/foo.gif"
          expect(event.get("[url][port]")).to eql 80
          expect(event.get("[url][domain]")).to eql "www.example.com"
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with urldecode processor' do
      let(:pipeline_processor) {
        '{
          "urldecode": {
            "field": "url_to_decode"
          }
        }'
      }

      it 'decodes the URL encoded string' do
        events = [LogStash::Event.new(
          "message" => "Decodes [url_to_decode] URL.",
          "url_to_decode" => "elastic.co/E%26L%26K-stack",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("url_to_decode")).to eql "elastic.co/E&L&K-stack"
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with user-agent processor' do
      let(:pipeline_processor) {
        '{
          "user_agent" : {
            "field" : "user_agent_field"
          }
        }'
      }

      it 'parses the user agent field' do
        events = [LogStash::Event.new(
          "message" => "Parses [user_agent_field] field.",
          "user_agent_field" => "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/51.0.2704.103 Safari/537.36",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("[user_agent][name]")).to eql "Chrome"
          expect(event.get("[user_agent][device][name]")).to eql "Mac"
          expect(event.get("[user_agent][version]")).to eql "51.0.2704.103"
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end

    end

    describe 'with geoip processor' do
      let(:settings) {
        super().merge(
          "geoip_database_directory" => "src/test/resources/co/elastic/logstash/filters/elasticintegration/geoip/databases"
        )
      }
      let(:pipeline_processor) {
        '{
          "geoip" : {
            "field" : "ip"
          }
        }'
      }

      it 'resolves IP geo-location information' do
        events = [LogStash::Event.new(
          "message" => "IP address in Sweden, Europe.",
          "ip" => "89.160.20.128",
          "data_stream" => data_stream)]

        subject.multi_filter(events).to_a.tap do |filter_result|
          expect(filter_result.size).to eql 1
          filter_result.first.tap do |event|
            aggregate_failures "geo enrichment (#{event.to_hash_with_metadata.inspect})" do
              expect(event.get("[geoip][continent_name]")).to eql "Europe"
              expect(event.get("[geoip][country_name]")).to eql "Sweden"
              expect(event.get("[geoip][country_iso_code]")).to eql "SE"
              expect(event.get("[geoip][city_name]")).to eql "Tumba"
              expect(event.get("[geoip][region_iso_code]")).to eql "SE-AB"
              expect(event.get("[geoip][region_name]")).to eql "Stockholm"
              expect(event.get("[geoip][location][lat]")).to be_within(0.01).of(59.2)    # ~1km
              expect(event.get("[geoip][location][lon]")).to be_within(0.02).of(17.8167) # ~1km @ lat 60
            end
          end 
        end
      end
    end

  end

  context '#multi-pipeline execution' do
    before(:each) do
      subject.register
    end

    describe 'with pipeline processor' do
      let(:pipeline_processor) {
        '{
          "split": {
            "field": "split_and_sort_field",
            "separator": ","
          }
        },
        {
          "sort": {
            "field": "split_and_sort_field",
            "order": "desc"
          }
        }'
      }

      it 'splits the array and sorts' do
        events = [LogStash::Event.new(
          "message" => "[split_and_sort_field] will be split first and sorted in descending order.",
          "split_and_sort_field" => "1,3,8,2,4,5,6,7,8",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("split_and_sort_field")).to eql %w[8 8 7 6 5 4 3 2 1]
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end
    end
  end

  context '#failures' do

    before(:each) do
      subject.register
    end

    describe 'with grok pattern processor' do
      let(:pipeline_processor) {
        '{
          "grok": {
            "field": "not_exist_field",
            "patterns": ["%{IP:client} %{WORD:method} %{URIPATHPARAM:request} %{NUMBER:bytes:int} %{NUMBER:duration:double}"]
          }
        }'
      }

      it 'fails when field does not exist and adds failure reason to metadata' do
        events = [LogStash::Event.new(
          "message" => "55.3.244.1 GET /index.html 15824 0.043",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expected_message = "java.lang.IllegalArgumentException: field [not_exist_field] not present as part of path [not_exist_field]"
          metadata_failure_reason = event.get("[@metadata][_ingest_pipeline_failure]")

          expect(metadata_failure_reason.nil?).to be_falsey
          expect(metadata_failure_reason["exception"].nil?).to be_falsey
          expect(metadata_failure_reason["message"].nil?).to be_falsey
          expect(metadata_failure_reason["message"]).to eql expected_message
        end
      end

    end

    describe 'with fail processor' do
      let(:pipeline_processor) {
        '{
          "fail": {
            "if" : "ctx.tags.contains(\'production\') != true",
            "message": "The production tag is not present, found tags: {{{tags}}}"
          }
        }'
      }

      it 'raises an exception when condition is met' do
        events = [LogStash::Event.new(
          "message" => "Test",
          "ctx" => { "tags" => %w[dev stage] },
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("[@metadata][_ingest_pipeline_failure][exception]").nil?).to be_falsey
          expect(event.get("[@metadata][_ingest_pipeline_failure][exception]")).to eql "org.elasticsearch.ingest.IngestProcessorException"
        end
      end

    end
  end

  context '#privileges' do
    # a user who doesn't have pipeline privileges
    let(:integ_user_name) {
      "ls_integration_tests_user"
    }
    let(:integ_user_password) {
      "l0ng-r4nd0m-p@ssw0rd"
    }

    let(:ls_integration_tests_user_info) {
      '{
        "password" : "' + integ_user_password + '",
        "roles" : [ "ls_integration_tests_role" ],
        "full_name" : "Logstash Integration",
        "email" : "ls_integration@elastic.co",
        "metadata" : {
          "intelligence" : 7
        }
      }'
    }

    let(:pipeline_processor) {
      '{
          "append": {
            "field": "append_field",
            "value": ["integration", "test"]
          }
        }'
    }

    before(:each) do
      es_http_client.post(get_host_port_for_es_client + "/_security/role/ls_integration_tests_role", :body => ls_integration_tests_role, :headers => { "Content-Type" => "application/json" }).call
      es_http_client.post(get_host_port_for_es_client + "/_security/user/" + integ_user_name, :body => ls_integration_tests_user_info, :headers => { "Content-Type" => "application/json" }).call
    end

    after(:each) do
      es_http_client.delete(get_host_port_for_es_client + "/_security/user/" + integ_user_name).call
      es_http_client.delete(get_host_port_for_es_client + "/_security/role/ls_integration_tests_role").call
    end

    describe 'with an unprivileged role' do
      # a role with empty privileges
      let(:ls_integration_tests_role) {
        '{
          "run_as": [],
          "cluster": [],
          "indices": []
        }'
      }

      it "ensures that user doesn't have privileges to read pipelines and cannot execute pipeline" do
        # plugin register fails
        expected_message = "The cluster privilege `monitor` is REQUIRED in order to validate Elasticsearch license"
        expect{ subject.register }.to raise_error(LogStash::ConfigurationError).with_message(expected_message)

        # send event and check
        events = [LogStash::Event.new(
          "message" => "'integration' and 'test' are to append to [append_field] field.",
          "append_field" => "Append to me.",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("[@metadata][_ingest_pipeline_failure][message]")).to include("action [indices:admin/index_template/simulate] is unauthorized for user [ls_integration_tests_user]")
        end
      end
    end

    describe 'with a privileged user' do
      # a role with ["monitor", "read_pipeline", "manage_index_templates"] privileges
      let(:ls_integration_tests_role) {
        '{
          "run_as": [],
          "cluster": ["monitor", "read_pipeline", "manage_index_templates"],
          "indices": []
        }'
      }

      it 'appends values to the field' do
        expect{ subject.register }.not_to raise_error

        expected_append_field = ["Append to me.","integration","test"]
        events = [LogStash::Event.new(
          "message" => "'integration' and 'test' are to append to [append_field] field.",
          "append_field" => "Append to me.",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("append_field")).to eql expected_append_field
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end
    end

  end

  context '#emulating real scenario' do
    let(:index_settings) {
      {
        "type" => "log",
        "dataset" => "tomcat.events",
        "namespace" => "default"
      }
    }

    let(:pipeline_processors_setting) {
      '{
        "processors": [
         {
           "grok": {
              "field": "message",
              "patterns": ["%{TOMCATLOG}"]
            }
         },
         {
            "date": {
              "field": "timestamp",
              "formats": [
                "yyyy-MM-dd HH:mm:ss,SSS ZZZ"
              ]
            }
         },
         {
            "remove": {
              "field": "message"
            }
         }
        ]
      }'
    }

    let(:index_pattern) {
      "logs-tomcat-*"
    }

    before(:each) do
      subject.register
    end

    it 'processes tomcat logs' do
      events = [
        LogStash::Event.new(
          "message" => "2023-03-16 16:32:37,706 +0500 | DEBUG | o.s.b.w.s.ServletContextInitializerBeans - Mapping filters: characterEncodingFilter urls=[/*] order=-2147483648, formContentFilter urls=[/*] order=-9900, requestContextFilter urls=[/*] order=-105",
          "data_stream" => data_stream),
        LogStash::Event.new(
          "message" => "2023-03-16 16:32:40,212 +0500 | WARN | JpaBaseConfiguration$JpaWebConfiguration - spring.jpa.open-in-view is enabled by default. Therefore, database queries may be performed during view rendering. Explicitly configure spring.jpa.open-in-view to disable this warning",
          "data_stream" => data_stream),
        LogStash::Event.new(
          "message" => "2023-03-16 17:36:10,957 +0500 | WARN | com.zaxxer.hikari.pool.HikariPool - HikariPool-1 - Thread starvation or clock leap detected (housekeeper delta=17m631ms).",
          "data_stream" => data_stream),
        LogStash::Event.new(
          "message" => "2023-03-16 16:32:40,399 +0500 | INFO | o.s.b.w.embedded.tomcat.TomcatWebServer - Tomcat started on port(s): 8080 (http) with context path ''",
          "data_stream" => data_stream),
        LogStash::Event.new(
          "message" => "2023-03-16 18:26:33,267 +0500 | ERROR | o.a.c.c.C.DispatcherServlet - Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception [Request processing failed; nested exception is uz.tatu.hotelbookingservice.common.exceptions.NoHotelFoundException: Hotel ID not found: 5] with root cause
            uz.tatu.hotelbookingservice.common.exceptions.NoHotelFoundException: Hotel ID not found: 5
            at uz.tatu.hotelbookingservice.service.HotelBookingService.hotel(HotelBookingService.java:27) ~[classes/:na]
            ...",
          "data_stream" => data_stream)
      ]

      subject.multi_filter(events).each do |event|
        if event.get("tags")
          expect(event.get("tags")).to include("_ingest_pipeline_failure")
          expect(event.get("[@metadata][target_ingest_pipeline]")).to eql '_none'
        end
      end
    end

  end

  context '#SSL' do

    # all test cases are using CA since plugin requires enterprise license
    # we need to test invalid CA and/or certificate cases make sure the plugin has a safety net
    describe 'when using invalid certificate' do

      let(:settings) {
        super().merge(
          # certificate is signed with localhost/127.0.0.1, should complain
          "ssl_verification_mode" => "full"
        )
      }

      # just need to fill the params, we don't/can't send any request to ES
      let(:pipeline_processor) {
        '{
          "dissect": {
            "field": "dissect_field",
            "pattern" : "%{clientip} %{ident} %{auth} [%{@timestamp}] \"%{verb} %{request} HTTP/%{httpversion}\" %{status} %{size}"
          }
        }'
      }

      it 'raises SSL related error' do
        expect { subject.register }
          .to(raise_error do |error|
            expect(error).to be_a(LogStash::ConfigurationError)
            expect(error.message).to include("Host name 'elasticsearch' does not match the certificate subject provided by the peer")
          end)
      end
    end
  end

end