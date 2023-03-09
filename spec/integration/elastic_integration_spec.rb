require 'spec_helper'
require 'manticore'
require 'logstash/filters/elastic_integration'

describe 'Logstash executes ingest pipeline', :integration => true do

  let(:es_http_client) { Manticore::Client.new }
  let(:es_client_connection_setting) {
    "http://elasticsearch:9200"
  }

  let(:settings) {
    {
      "hosts" => "http://elasticsearch:9200"
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
        '+ pipeline_processor + '
      ]
    }'
  }
  let(:pipeline_processor) {{}}

  let(:index_template_setting) { '
    {
      "index_patterns": ["logs-logstash-*"],
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
    ingest_pipeline_url = es_client_connection_setting + "/_ingest/pipeline/" + pipeline_setting + "-pipeline"
    es_http_client.put(ingest_pipeline_url, :body => pipeline_processors_setting, :headers => { "Content-Type" => "application/json" }).call

    # create an index template by setting default pipeline
    index_template_url = es_client_connection_setting + "/_index_template/" + pipeline_setting
    es_http_client.put(index_template_url, :body => index_template_setting, :headers => { "Content-Type" => "application/json" }).call

    allow(elastic_integration_plugin.logger).to receive(:trace)
    allow(elastic_integration_plugin.logger).to receive(:debug)
    subject.register
  end

  after(:each) do
    # remove index template
    index_template_url = es_client_connection_setting + "/_index_template/" + pipeline_setting
    es_http_client.delete(index_template_url).call

    # remove ingest pipeline
    ingest_pipeline_url = es_client_connection_setting + "/_ingest/pipeline/" + pipeline_setting + "-pipeline"
    es_http_client.delete(ingest_pipeline_url).call

    subject.close
  end

  context '#pipeline execution' do

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
          expect(event.get("append_field") == expected_append_field).to be_truthy
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
          expect(event.get("size").eql?(2048)).to be_truthy
        end
      end

    end

    # TODO: failed with no processor found error
    #   [2023-03-09T10:04:24,951][ERROR][co.elastic.logstash.filters.elasticintegration.IngestPipelineFactory] failed to create ingest pipeline `integration-logstash_test.events-default-pipeline` from pipeline configuration
    #     org.elasticsearch.ElasticsearchParseException: No processor type exists with name [circle]
    describe 'with circle processor' do
      let(:pipeline_processor) {
        '{
          "circle": {
            "field": "circle_field",
            "error_distance": 28.0,
            "shape_type": "geo_shape"
          }
        }'
      }

      it 'converts circle definitions of shapes to regular polygons' do
        events = [LogStash::Event.new(
          "message" => "Convert circle definition.",
          "circle_field": "CIRCLE (30 10 40)",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          puts "Event: #{event.to_json.to_s}"
          expect(event.get("circle_field").include?("POLYGON")).to be_truthy
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
          expect(event.get("id").eql?(200)).to be_truthy
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
          expect(event.get("csv_name_parsed").eql?("Elephant")).to be_truthy
          expect(event.get("csv_email_parsed").eql?("elephant@example.com")).to be_truthy
          expect(event.get("csv_phone_parsed").eql?("111-222-3344")).to be_truthy
          expect(event.get("csv_address_parsed").eql?("Elephant's address.")).to be_truthy
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
          expect(event.get("timestamp").eql?("2023-03-08T09:10:17.000+01:00")).to be_truthy
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
          expect(event.get("_index").include?("<monthly-index-{2023-03-08")).to be_truthy
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
          expect(event.get("clientip").eql?("1.2.3.4")).to be_truthy
          expect(event.get("ident").eql?("-")).to be_truthy
          expect(event.get("auth").eql?("-")).to be_truthy
          expect(event.get("@timestamp").eql?("01/Apr/2023:22:00:52 +0000")).to be_truthy
          expect(event.get("verb").eql?("GET")).to be_truthy
          expect(event.get("request").eql?("/path/to/some/resources/test.gif")).to be_truthy
          expect(event.get("httpversion").eql?("1.0")).to be_truthy
          expect(event.get("status").eql?("200")).to be_truthy
          expect(event.get("size").eql?("3171")).to be_truthy
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
          expect(event.get("[foo][bar]").eql?("foo -> bar value")).to be_truthy
          expect(event.get("[parent][child]").eql?("parent -> child value")).to be_truthy
        end
      end

    end

    # TODO : not dropping the event
    describe 'with drop processor' do
      let(:pipeline_processor) {
        '{
          "drop": {
            "if": "ctx.remove_user_type == \'Guest\'"
          }
        }'
      }

      it 'drops an event (when condition satisfies)' do
        events = [LogStash::Event.new(
                    "message" => "This event is going to be dropped.",
                    "remove_user_type" => "Guest",
                    "data_stream" => data_stream),
                  LogStash::Event.new(
                    "message" => "This event is NOT going to be dropped.",
                    "remove_user_type" => "Authorized",
                    "data_stream" => data_stream)
        ]

        subject.multi_filter(events).each do |event|
          puts "Event: #{event.to_json.to_s}"
          # no need to check size since we are removing all guests
          expect(event.get("[remove_user_type]").eql?("Authorized")).to be_truthy
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
          expect(event.get("tags").include?("_ingest_pipeline_failure").nil?).to be_falsey
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
          expect(event.get("fingerprint").eql?("XqSwreW5FVPwjCF9pB7tzX6fQBs=")).to be_truthy
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
          expect(event.get("client").eql?("55.3.244.1")).to be_truthy
          expect(event.get("method").eql?("GET")).to be_truthy
          expect(event.get("request").eql?("/index.html")).to be_truthy
          expect(event.get("bytes").eql?(15824)).to be_truthy
          expect(event.get("duration").eql?(0.043)).to be_truthy
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
        end
      end

    end

    # TODO: need a fix
    #   Failure/Error: Unable to find org.elasticsearch.ingest.common.HtmlStripProcessor.process(org/elasticsearch/ingest/common/HtmlStripProcessor.java to read failed line
    #      Java::JavaLang::NoClassDefFoundError:
    #        org/apache/lucene/analysis/charfilter/HTMLStripCharFilter
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
          puts "Event: #{event.to_json.to_s}"
          # expect(event.get("strip_field") == "\n HTML \n \n \n \n fast, and brutal \n \n \n").to be_truthy
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
          expect(event.get("joined_array_field").eql?("1-2-3-4")).to be_truthy
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
          expect(event.get("[json_target][foo]").eql?(2000)).to be_truthy
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
          expect(event.get("ip").eql?("1.2.3.4")).to be_truthy
          expect(event.get("error").eql?("REFUSED")).to be_truthy
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
          expect(event.get("cats").eql?(expected_message)).to be_truthy
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
          expect(event.get("[network][direction]").eql?("inbound")).to be_truthy
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

        subject.multi_filter(events).each do |event|\
          expect(event.get("[url][top_level_domain]").eql?("ac.uk")).to be_truthy
          expect(event.get("[url][subdomain]").eql?("www")).to be_truthy
          expect(event.get("[url][registered_domain]").eql?("example.ac.uk")).to be_truthy
          expect(event.get("[url][domain]").eql?("www.example.ac.uk")).to be_truthy
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
          expect(event.get("tags").include?("_ingest_pipeline_failure")).to be_truthy
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
          expect(event.get("rename_field_from").nil?).to be_truthy
          expect(event.get("rename_field_to").nil?).to be_falsey
          expect(event.get("rename_field_to").eql?("I have to be renamed to rename_field_to.")).to be_truthy
        end
      end

    end

    # TODO: not working
    describe 'with script processor' do
      let(:pipeline_processor) {
        '{
          "script": {
            "description": "Extract tags from env field",
            "lang": "painless",
            "source": """
              String[] envSplit = ctx[\'env\'].splitOnToken(params[\'delimiter\']);
              ArrayList tags = new ArrayList();
              tags.add(envSplit[params[\'position\']].trim());
              ctx[\'tags\'] = tags;
            """,
            "params": {
              "delimiter": "-",
              "position": 1
            }
          }
        }'
      }

      it 'runs painless script on a given field' do
        events = [LogStash::Event.new(
          "message" => "Should extract prod tag from env field",
          "env" => "es01-prod",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          puts "Event: #{event.to_json.to_s}"
          expect(event.get("[tags][prod]").eql?("prod")).to be_truthy
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
          expect(event.get("elephant_age").eql?(expected_value)).to be_truthy
        end
      end

    end

    describe 'with set-security-user processor' do
      let(:pipeline_processor) {
        '{
          "set_security_user": {
            "field": "user"
          }
        }'
      }

      it 'fills user field with required properties' do
        events = [LogStash::Event.new(
          "message" => "User information will be added to [user] field.",
          "data_stream" => data_stream)]

        subject.multi_filter(events).each do |event|
          expect(event.get("[user][username]").nil?).to be_falsey
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
          expect(event.get("array_field_to_sort").eql?([8,4,3,2,1])).to be_truthy
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
          expect(event.get("split_field").eql?(%w[1 2 3 4 5 6 7 8])).to be_truthy
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
          expect(event.get("trim_field").eql?("Trimming the field")).to be_truthy
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
          expect(event.get("dogs").eql?(expected_message)).to be_truthy
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
          expect(event.get("[url][path]").eql?("/foo.gif")).to be_truthy
          expect(event.get("[url][port]").eql?(80)).to be_truthy
          expect(event.get("[url][domain]").eql?("www.example.com")).to be_truthy
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
          expect(event.get("url_to_decode").eql?("elastic.co/E&L&K-stack")).to be_truthy
        end
      end

    end

    # TODO: creating processor fails
    #   [2023-03-09T09:57:52,947][ERROR][co.elastic.logstash.filters.elasticintegration.IngestPipelineFactory] failed to create ingest pipeline `integration-logstash_test.events-default-pipeline` from pipeline configuration
    #    org.elasticsearch.ElasticsearchParseException: No processor type exists with name [user_agent]
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
          expect(event.get("[user_agent_field][name]").eql?("Chrome")).to be_truthy
          expect(event.get("[user_agent_field][device][name]").eql?("Mac")).to be_truthy
          expect(event.get("[user_agent_field][version]").eql?("51.0.2704.103")).to be_truthy
        end
      end

    end

  end

  context '#multipipeline' do
    # TODO: pipeline processor
  end

  # TODO:
  #   multiple processors and failure (cannot execute) cases
  #   data stream without default processor
  #   version is higher than ES treeish
  context '#fails' do
    describe "when field doesn't exist" do

    end
  end

end