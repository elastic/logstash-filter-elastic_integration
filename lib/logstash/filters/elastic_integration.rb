# encoding: utf-8
require "logstash/filters/base"
require "logstash/namespace"

require_relative "elastic_integration/jar_dependencies"

class LogStash::Filters::ElasticIntegration < LogStash::Filters::Base

  config_name "elastic_integration"

  java_import('co.elastic.logstash.filters.elasticintegration.PluginConfiguration')

  ELASTICSEARCH_DEFAULT_PORT = 9200.freeze
  ELASTICSEARCH_DEFAULT_PATH = '/'.freeze

  # Sets the host(s) of the remote instance. If given an array it will load balance requests across the hosts specified in the `hosts` parameter.
  # Remember the `http` protocol uses the http://www.elastic.co/guide/en/elasticsearch/reference/current/modules-http.html#modules-http[http] address (eg. 9200, not 9300).
  #     `"127.0.0.1"`
  #     `["127.0.0.1:9200","127.0.0.2:9200"]`
  #     `["http://127.0.0.1"]`
  #     `["https://127.0.0.1:9200"]`
  #     `["https://127.0.0.1:9200/mypath"]` (If using a proxy on a subpath)
  # It is important to exclude http://www.elastic.co/guide/en/elasticsearch/reference/current/modules-node.html[dedicated master nodes] from the `hosts` list
  # to prevent LS from sending bulk requests to the master nodes. So this parameter should only reference either data or client nodes in Elasticsearch.
  #
  # Any special characters present in the URLs here MUST be URL escaped! This means `#` should be put in as `%23` for instance.
  config :hosts, :validate => :uri, :list => true

  # Cloud ID, from the Elastic Cloud web console. If set `hosts` should not be used.
  #
  # For more details, check out the https://www.elastic.co/guide/en/logstash/current/connecting-to-cloud.html#_cloud_id[cloud documentation]
  config :cloud_id, :validate => :string

  # Enable SSL/TLS secured communication to Elasticsearch cluster
  config :ssl, :validate => :boolean, :default => true

  # This option needs to be used with `ssl_certificate_authorities` and a defined list of CAs
  config :ssl_verification_mode, :validate => %w(full certificate none), :default => "full"

  # A path to truststore
  config :truststore, :validate => :path

  # A password for truststore
  config :truststore_password, :validate => :password

  # list of paths for SSL certificate authorities
  config :ssl_certificate_authorities, :validate => :path, :list => true

  # a path for SSL certificate which will be used when SSL is enabled
  config :ssl_certificate, :validate => :path

  # a path for SSL certificate key
  config :ssl_key, :validate => :path

  # SSL keyphrase
  config :ssl_key_passphrase, :validate => :password

  # The keystore used to present a certificate to the server
  config :keystore, :validate => :path

  # A password for keystore
  config :keystore_password, :validate => :password

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

    validate_ssl_settings!
    validate_connection_settings!
    validate_auth_settings!

    @plugin_config = extract_immutable_config
  end # def register

  def filter(event)
    # bootstrap placeholder no-op
  end

  private

  def validate_connection_settings!
    @cloud_id = @cloud_id&.freeze

    if @hosts
      scheme = @ssl ? "https".freeze : "http".freeze
      @hosts = @hosts.each do |host_uri|
        # no need to validate hostname, uri validates it at initialize
        host_uri.port=(ELASTICSEARCH_DEFAULT_PORT) if host_uri.port.nil?
        host_uri.path=(ELASTICSEARCH_DEFAULT_PATH) if host_uri.path.length == 0 # host_uri.path may return empty array and will not be nil
        host_uri.update(:scheme, scheme) if host_uri.scheme.nil? || host_uri.scheme.empty?
        host_uri.freeze
      end.freeze

      agree_with = @hosts.all? { |host| host && host.scheme == scheme }
      raise_config_error! "All hosts must agree with #{scheme} schema when#{@ssl ? '' : ' NOT'} using `ssl`." unless agree_with
    end

    raise_config_error! "`hosts` and `cloud_id` cannot be used together." if @hosts && @cloud_id
    raise_config_error! "Either `hosts` or `cloud_id` is required" unless @hosts || @cloud_id
    raise_config_error! "Empty `cloud_id` is not allowed" if @cloud_id && @cloud_id.empty?
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

    @logger.warn("Credentials are being sent over unencrypted HTTP. This may bring security risk.") if possible_auth_options.size == 1 && !@ssl
  end

  def validate_ssl_settings!
    @ssl                         = @ssl.freeze # has a default value
    @ssl_verification_mode       = @ssl_verification_mode.freeze # has a default value
    @truststore                  = @truststore&.freeze
    @truststore_password         = @truststore_password&.freeze
    @ssl_certificate             = @ssl_certificate&.freeze
    @ssl_key                     = @ssl_key&.freeze
    @ssl_key_passphrase          = @ssl_key_passphrase&.freeze
    @keystore                    = @keystore&.freeze
    @keystore_password           = @keystore_password&.freeze
    @ssl_certificate_authorities = @ssl_certificate_authorities&.freeze

    if @ssl
      # optional: presenting our identity
      raise_config_error! "`ssl_certificate` and `keystore` cannot be used together." if @ssl_certificate && @keystore
      raise_config_error! "`ssl_certificate` requires `ssl_key`" if @ssl_certificate && !@ssl_key
      ensure_readable_and_non_writable! "ssl_certificate", @ssl_certificate if @ssl_certificate

      raise_config_error! "`ssl_key` is not allowed unless `ssl_certificate` is specified" if @ssl_key && !@ssl_certificate
      raise_config_error! "`ssl_key` requires `ssl_key_passphrase`" if @ssl_key && !@ssl_key_passphrase
      ensure_readable_and_non_writable! "ssl_key", @ssl_key if @ssl_key

      raise_config_error! "`ssl_key_passphrase` is not allowed unless `ssl_key` is specified" if @ssl_key_passphrase && !@ssl_key
      raise_config_error! "`ssl_key_passphrase` cannot be empty" if @ssl_key_passphrase && @ssl_key_passphrase.value.empty?

      raise_config_error! "`keystore` requires `keystore_password`" if @keystore && !@keystore_password
      raise_config_error! "`keystore_password` is not allowed unless `keystore` is specified" if @keystore_password && !@keystore
      raise_config_error! "`keystore_password` cannot be empty" if @keystore_password && @keystore_password.value.empty?
      ensure_readable_and_non_writable!"keystore", @keystore if @keystore

      # establishing trust of the server we connect to
      # system-provided trust requires verification mode enabled
      if @ssl_verification_mode == "none"
        raise_config_error! "`truststore` requires `ssl_verification_mode` to be either `full` or `certificate`" if @truststore
        raise_config_error! "`truststore_password` requires `truststore` and `ssl_verification_mode` (either `full` or `certificate`)" if @truststore_password
        raise_config_error! "`ssl_certificate_authorities` requires `ssl_verification_mode` to be either `full` or `certificate`" if @ssl_certificate_authorities
      end

      raise_config_error! "`truststore` and `ssl_certificate_authorities` cannot be used together." if @truststore && @ssl_certificate_authorities
      raise_config_error! "`truststore` requires `truststore_password`" if @truststore && !@truststore_password
      ensure_readable_and_non_writable!"truststore", @truststore if @truststore

      raise_config_error! "`truststore_password` is not allowed unless `truststore` is specified" if !@truststore && @truststore_password
      raise_config_error! "`truststore_password` cannot be empty" if @truststore_password && @truststore_password.value.empty?

      if !@truststore && @ssl_certificate_authorities&.empty?
        raise_config_error! "`ssl_certificate_authorities` cannot be empty"
      end
      @ssl_certificate_authorities&.each do |certificate_authority|
        ensure_readable_and_non_writable!"ssl_certificate_authorities", certificate_authority
      end
    else
      # Disabled SSL does not allow to set SSL related configs
      ssl_config_provided = original_params.keys.select {|k| k.start_with?("ssl_", "keystore", "truststore", "cloud_id") }
      if ssl_config_provided.any?
        raise_config_error! "When ssl is disabled, the following provided parameters are not allowed: #{ssl_config_provided}"
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

    builder.setHosts @hosts#&.map(&:to_s)
    builder.setCloudId @cloud_id

    builder.setSslEnabled @ssl

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

end