# encoding: utf-8
require "logstash/filters/base"
require "logstash/namespace"

require_relative "elastic_integration/jar_dependencies"

class LogStash::Filters::ElasticIntegration < LogStash::Filters::Base

  require_relative "elastic_integration/event_api_bridge"
  include EventApiBridge

  config_name "elastic_integration"

  java_import('co.elastic.logstash.filters.elasticintegration.PluginConfiguration')
  java_import('co.elastic.logstash.filters.elasticintegration.EventProcessor')
  java_import('co.elastic.logstash.filters.elasticintegration.EventProcessorBuilder')
  java_import('co.elastic.logstash.filters.elasticintegration.ElasticsearchRestClientBuilder')
  java_import('co.elastic.logstash.filters.elasticintegration.PreflightCheck')

  ELASTICSEARCH_DEFAULT_PORT = 9200.freeze
  ELASTICSEARCH_DEFAULT_PATH = '/'.freeze
  HTTP_PROTOCOL = "http".freeze
  HTTPS_PROTOCOL = "https".freeze

  # Sets the host(s) of the remote instance. If given an array it will load balance
  # requests across the hosts specified in the `hosts` parameter. Hosts can be any of
  # the forms:
  #     `"127.0.0.1"`
  #     `["127.0.0.1:9200","127.0.0.2:9200"]`
  #     `["http://127.0.0.1"]`
  #     `["https://127.0.0.1:9200"]`
  #     `["https://127.0.0.1:9200/mypath"]` (If using a proxy on a subpath)
  # If the protocol is unspecified, this plugin assumes `https` when `ssl => true` (default)
  # or `http` when `ssl => false`.
  #
  # It is important to exclude http://www.elastic.co/guide/en/elasticsearch/reference/current/modules-node.html[dedicated master nodes] from the `hosts` list
  # to prevent LS from overloading the master nodes. So this parameter should only reference either data or client nodes in Elasticsearch.
  #
  # Any special characters present in the URLs here MUST be URL escaped! This means `#` should be put in as `%23` for instance.
  config :hosts, :validate => :uri, :list => true

  # Cloud ID, from the Elastic Cloud web console. If set `hosts` should not be used.
  #
  # For more details, check out the https://www.elastic.co/guide/en/logstash/current/connecting-to-cloud.html#_cloud_id[cloud documentation]
  config :cloud_id, :validate => :string

  # Enable SSL/TLS secured communication to Elasticsearch cluster
  config :ssl_enabled, :validate => :boolean

  # Determines how much to verify a presented SSL certificate when `ssl => true`
  #  - none: no validation
  #  - certificate: trustworthy certificate (identity claims NOT validated)
  #  - full (default): trustworthy certificate WITH validated identity claims
  config :ssl_verification_mode, :validate => %w(full certificate none)

  # A path to truststore, used to _override_ the system truststore
  config :ssl_truststore_path, :validate => :path

  # A password for truststore
  config :ssl_truststore_password, :validate => :password

  # list of paths for SSL certificate authorities, used to _override_ the system truststore
  config :ssl_certificate_authorities, :validate => :path, :list => true

  # a path for SSL certificate which will be used when SSL is enabled
  config :ssl_certificate, :validate => :path

  # a path for SSL certificate key
  config :ssl_key, :validate => :path

  # SSL keyphrase
  config :ssl_key_passphrase, :validate => :password

  # The keystore used to present a certificate to the server
  config :ssl_keystore_path, :validate => :path

  # A password for SSL keystore
  config :ssl_keystore_password, :validate => :password

  # Username for basic authentication
  config :auth_basic_username, :validate => :string

  # Password for basic authentication
  config :auth_basic_password, :validate => :password

  # Cloud authentication string ("<username>:<password>" format) to connect to Elastic cloud.
  #
  # For more details, check out the https://www.elastic.co/guide/en/logstash/current/connecting-to-cloud.html#_cloud_auth[cloud documentation]
  config :cloud_auth, :validate => :password

  # A key to authenticate when connecting to Elasticsearch
  config :api_key, :validate => :password

  def register
    @logger.debug("Registering `filter-elastic_integration` plugin.", :params => original_params)

    validate_connection_settings!
    @ssl_enabled = infer_ssl_from_connection_settings if @ssl_enabled.nil?

    validate_ssl_settings!
    validate_auth_settings!
    validate_and_normalize_hosts

    initialize_event_processor!

    perform_preflight_check!
  end # def register

  def filter(event)
    fail "#{self.class}#filter is not allowed. Use #{self.class}#multi_filter"
  end

  def multi_filter(ruby_api_events)
    LogStash::Util.set_thread_plugin(self)

    incoming_java_api_events = ruby_events_as_java(ruby_api_events)

    outgoing_java_api_events = @event_processor.process_events(incoming_java_api_events)

    java_events_as_ruby(outgoing_java_api_events)
  end

  def filter_matched_java(java_event)
    filter_matched(mutable_ruby_view_of_java_event(java_event))
  end

  def close
    @elasticsearch_rest_client&.close
    @event_processor&.close
  end

  private

  def validate_connection_settings!
    @cloud_id = @cloud_id&.freeze

    raise_config_error! "`hosts` and `cloud_id` cannot be used together." if @hosts && @cloud_id
    raise_config_error! "Either `hosts` or `cloud_id` is required" unless @hosts || @cloud_id
    raise_config_error! "Empty `cloud_id` is not allowed" if @cloud_id && @cloud_id.empty?
    raise_config_error! "Empty `hosts` is not allowed" if @hosts && @hosts.size == 0 # let's also catch [""]
  end

  def infer_ssl_from_connection_settings
    return true if @cloud_id
    return true if @hosts.all? { |host| host.scheme.to_s.empty? }
    return true if @hosts.all? { |host| host.scheme == HTTPS_PROTOCOL }
    return false if @hosts.all? { |host| host.scheme == HTTP_PROTOCOL }

    raise_config_error! "`hosts` contains entries with mixed protocols, which are unsupported; when any entry includes a protocol, the protocols of all must match each other"
  end

  def validate_and_normalize_hosts
    return if @hosts.nil? || @hosts.size == 0

    # host normalization expects `ssl_enabled` to be resolved (not nil)
    # let's add a safeguard to make sure we don't break the behavior in the future
    raise_config_error! "`hosts` cannot be normalized with `ssl_enabled => nil`" if @ssl_enabled.nil?

    root_path = @hosts[0].path.empty? ? ELASTICSEARCH_DEFAULT_PATH : @hosts[0].path
    scheme = @ssl_enabled ? HTTPS_PROTOCOL : HTTP_PROTOCOL

    @hosts = @hosts.each do |host_uri|
      # no need to validate hostname, uri validates it at initialize
      host_uri.port=(ELASTICSEARCH_DEFAULT_PORT) if host_uri.port.nil?
      host_uri.path=(ELASTICSEARCH_DEFAULT_PATH) if host_uri.path.to_s.empty?
      agree_with = host_uri.path == root_path
      raise_config_error! "All hosts must use same path." unless agree_with

      host_uri.update(:scheme, scheme) if host_uri.scheme.to_s.empty?
      agree_with = host_uri.scheme == scheme
      raise_config_error! "All hosts must agree with #{scheme} schema when#{@ssl_enabled ? '' : ' NOT'} using `ssl_enabled`." unless agree_with

      host_uri.freeze
    end.freeze
  end

  def validate_auth_settings!
    @cloud_auth           = @cloud_auth&.freeze
    @api_key              = @api_key&.freeze
    @auth_basic_username  = @auth_basic_username&.freeze
    @auth_basic_password  = @auth_basic_password&.freeze

    raise_config_error! "`auth_basic_username` requires `auth_basic_password`" if @auth_basic_username && !@auth_basic_password
    raise_config_error! "`auth_basic_password` is not allowed unless `auth_basic_username` is specified" if !@auth_basic_username && @auth_basic_password
    if @auth_basic_username && @auth_basic_password
      raise_config_error! "Empty `auth_basic_username` or `auth_basic_password` is not allowed" if @auth_basic_username.empty? || @auth_basic_password.value.empty?
    end

    possible_auth_options = original_params.keys & %w(auth_basic_password cloud_auth api_key)
    raise_config_error!("Multiple authentication #{possible_auth_options} options cannot be used together. Please provide ONLY one.") if possible_auth_options.size > 1

    raise_config_error! "Empty `cloud_auth` is not allowed" if @cloud_auth && @cloud_auth.value.empty?
    raise_config_error! "Empty `api_key` is not allowed" if @api_key && @api_key.value.empty?

    @logger.warn("Credentials are being sent over unencrypted HTTP. This may bring security risk.") if possible_auth_options.size == 1 && !@ssl_enabled
  end

  def validate_ssl_settings!
    @ssl_enabled                 = @ssl_enabled&.freeze
    @ssl_verification_mode       = @ssl_verification_mode&.freeze
    @ssl_certificate             = @ssl_certificate&.freeze
    @ssl_key                     = @ssl_key&.freeze
    @ssl_key_passphrase          = @ssl_key_passphrase&.freeze
    @ssl_truststore_path         = @ssl_truststore_path&.freeze
    @ssl_truststore_password     = @ssl_truststore_password&.freeze
    @ssl_keystore_path           = @ssl_keystore_path&.freeze
    @ssl_keystore_password       = @ssl_keystore_password&.freeze
    @ssl_certificate_authorities = @ssl_certificate_authorities&.freeze

    if @ssl_enabled
      # when SSL is enabled, the default ssl_verification_mode is "full"
      @ssl_verification_mode = "full".freeze if @ssl_verification_mode.nil?

      # optional: presenting our identity
      raise_config_error! "`ssl_certificate` and `ssl_keystore_path` cannot be used together." if @ssl_certificate && @ssl_keystore_path
      raise_config_error! "`ssl_certificate` requires `ssl_key`" if @ssl_certificate && !@ssl_key
      ensure_readable_and_non_writable! "ssl_certificate", @ssl_certificate if @ssl_certificate

      raise_config_error! "`ssl_key` is not allowed unless `ssl_certificate` is specified" if @ssl_key && !@ssl_certificate
      raise_config_error! "`ssl_key` requires `ssl_key_passphrase`" if @ssl_key && !@ssl_key_passphrase
      ensure_readable_and_non_writable! "ssl_key", @ssl_key if @ssl_key

      raise_config_error! "`ssl_key_passphrase` is not allowed unless `ssl_key` is specified" if @ssl_key_passphrase && !@ssl_key
      raise_config_error! "`ssl_key_passphrase` cannot be empty" if @ssl_key_passphrase && @ssl_key_passphrase.value.empty?

      raise_config_error! "`ssl_keystore_path` requires `ssl_keystore_password`" if @ssl_keystore_path && !@ssl_keystore_password
      raise_config_error! "`ssl_keystore_password` is not allowed unless `ssl_keystore_path` is specified" if @ssl_keystore_password && !@ssl_keystore_path
      raise_config_error! "`ssl_keystore_password` cannot be empty" if @ssl_keystore_password && @ssl_keystore_password.value.empty?
      ensure_readable_and_non_writable! "ssl_keystore_path", @ssl_keystore_path if @ssl_keystore_path

      # establishing trust of the server we connect to
      # system-provided trust requires verification mode enabled
      if @ssl_verification_mode == "none"
        raise_config_error! "`ssl_truststore_path` requires `ssl_verification_mode` to be either `full` or `certificate`" if @ssl_truststore_path
        raise_config_error! "`ssl_truststore_password` requires `ssl_truststore_path` and `ssl_verification_mode` (either `full` or `certificate`)" if @ssl_truststore_password
        raise_config_error! "`ssl_certificate_authorities` requires `ssl_verification_mode` to be either `full` or `certificate`" if @ssl_certificate_authorities
      end

      raise_config_error! "`ssl_truststore_path` and `ssl_certificate_authorities` cannot be used together." if @ssl_truststore_path && @ssl_certificate_authorities
      raise_config_error! "`ssl_truststore_path` requires `ssl_truststore_password`" if @ssl_truststore_path && !@ssl_truststore_password
      ensure_readable_and_non_writable! "ssl_truststore_path", @ssl_truststore_path if @ssl_truststore_path

      raise_config_error! "`ssl_truststore_password` is not allowed unless `ssl_truststore_path` is specified" if !@ssl_truststore_path && @ssl_truststore_password
      raise_config_error! "`ssl_truststore_password` cannot be empty" if @ssl_truststore_password && @ssl_truststore_password.value.empty?

      if !@ssl_truststore_path && @ssl_certificate_authorities&.empty?
        raise_config_error! "`ssl_certificate_authorities` cannot be empty"
      end
      @ssl_certificate_authorities&.each do |certificate_authority|
        ensure_readable_and_non_writable! "ssl_certificate_authorities", certificate_authority
      end
    else
      # Disabled SSL does not allow to set SSL related configs
      ssl_config_provided = original_params.keys.select {|k| k.start_with?("ssl_", "cloud_id") && k != "ssl_enabled" }
      if ssl_config_provided.any?
        raise_config_error! "When SSL is disabled, the following provided parameters are not allowed: #{ssl_config_provided}"
      end
    end
  end

  def ensure_readable_and_non_writable!(name, path)
    raise_config_error! "Specified #{name} #{path} path must be readable." unless File.readable?(path)
    raise_config_error! "Specified #{name} #{path} path must not be writable." if File.writable?(path)
  end

  ##
  # @param message [String]
  # @raise [LogStash::ConfigurationError]
  def raise_config_error!(message)
    raise LogStash::ConfigurationError, message
  end

  ##
  # Builds a `PluginConfiguration` from the previously-validated config
  def extract_immutable_config
    builder = PluginConfiguration::Builder.new

    builder.setId @id

    builder.setHosts @hosts&.map(&:to_s)
    builder.setCloudId @cloud_id

    builder.setSslEnabled @ssl_enabled

    # ssl trust
    builder.setSslVerificationMode @ssl_verification_mode
    builder.setSslTruststorePath @truststore
    builder.setSslTruststorePassword @truststore_password
    builder.setSslCertificateAuthorities @ssl_certificate_authorities

    # ssl identity
    builder.setSslKeystorePath @keystore
    builder.setSslKeystorePassword @keystore_password
    builder.setSslCertificate @ssl_certificate
    builder.setSslKey @ssl_key
    builder.setSslKeyPassphrase @ssl_key_passphrase

    # request auth
    builder.setAuthBasicUsername @auth_basic_username
    builder.setAuthBasicPassword @auth_basic_password
    builder.setCloudAuth @cloud_auth
    builder.setApiKey @api_key

    builder.build
  end

  def initialize_event_processor!
    @elasticsearch_rest_client = ElasticsearchRestClientBuilder.fromPluginConfiguration(extract_immutable_config)
                                                               .map(&:build)
                                                               .orElseThrow() # todo: ruby/java bridge better exception

    @event_processor = EventProcessorBuilder.fromElasticsearch(@elasticsearch_rest_client)
                                            .setFilterMatchListener(method(:filter_matched_java).to_proc)
                                            .build("logstash.filter.elastic_integration.#{id}.#{__id__}")
  rescue => exception
    raise_config_error!("configuration did not produce an EventProcessor: #{exception}")
  end

  def perform_preflight_check!
    PreflightCheck.new(@elasticsearch_rest_client).check
  rescue => e
    raise_config_error!(e.message)
  end
end