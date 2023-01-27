# encoding: utf-8
require "logstash/filters/base"
require "logstash/namespace"

class LogStash::Filters::ElasticIntegration < LogStash::Filters::Base

  config_name "elastic_integration"

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

  # Advanced TLS/SSL configuration
  config :ssl_cipher_suites, :validate => :string, :list => true
  config :ssl_supported_protocols, :validate => :string, :list => true

  def register
    logger.debug("Registering `filter-elastic_integration` plugin.")

    validate_ssl_settings!
    validate_connection_settings!

  end # def register

  def filter(event)
    # bootstrap placeholder no-op
  end

  private

  def validate_connection_settings!
    validate_host_settings!
    validate_basic_auth!

    @cloud_auth  = @cloud_auth&.freeze
    @api_key     = @api_key&.freeze
  end

  def validate_host_settings!
    return unless @hosts

    scheme = @ssl ? "https" : "http"
    agree_with = @hosts.all? { |host| host && host.scheme == scheme }
    raise_config_error! "All hosts must agree with #{scheme} schema when #{@ssl ? '' : 'NOT'} using `ssl`." unless agree_with

    @hosts = @hosts.each do |host_uri|
      # no need to validate hostname, uri validates it at initialize
      host_uri.port=(ELASTICSEARCH_DEFAULT_PORT) if host_uri.port.nil?
      host_uri.path=(ELASTICSEARCH_DEFAULT_PATH) if host_uri.path.length == 0 # host_uri.path may return empty array and will not be nil
      host_uri.freeze
    end.freeze
  end

  def validate_basic_auth!
    @auth_basic_username = @auth_basic_username&.freeze
    @auth_basic_password = @auth_basic_password&.freeze

    raise_config_error! "Using `auth_basic_username` requires `auth_basic_password`" if @auth_basic_username && !@auth_basic_password
    @logger.warn("Credentials are being sent over unencrypted HTTP. This may bring security risk.") unless @ssl
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
    @ssl_cipher_suites           = @ssl_cipher_suites&.freeze
    @ssl_supported_protocols     = @ssl_supported_protocols&.freeze
    @ssl_certificate_authorities = @ssl_certificate_authorities&.freeze

    # Category: Establishing trust of the server we connect to (requires ssl: true)
    raise_config_error! "Using `ssl_verification_mode` #{@ssl_verification_mode} requires `ssl` enabled" if @ssl_verification_mode != "none" && !@ssl
    if @ssl_verification_mode != "none"
      if @truststore
        raise_config_error! "Using `truststore` requires `truststore_password`" unless @truststore_password
        raise_config_error! "SSL credentials cannot be loaded from the specified #{@truststore} path. Please make the path readable." unless File.readable?(@truststore)
        raise_config_error! "Specified truststore #{@truststore} cannot be writable for security reasons." if File.writable?(@truststore)
      end

      @ssl_certificate_authorities&.each do |certificate_authority|
        raise_config_error! "Certificate authority cannot be loaded from the specified #{certificate_authority} path. Please make the path readable." unless File.readable?(certificate_authority)
        raise_config_error! "Specified certificate authority #{certificate_authority} cannot be writable for security reasons." if File.writable?(certificate_authority)
      end
    end # end of category

    # Category: Presenting our identity
    if @ssl
      if @ssl_certificate
        raise_config_error! "SSL certificate from the #{@ssl_certificate} path cannot be loaded. Please make the path readable." unless File.readable?(@ssl_certificate)
        raise_config_error! "Specified SSL certificate #{@ssl_certificate} path cannot be writable for security reasons." if File.writable?(@ssl_certificate)

        raise_config_error! "Using `ssl_key` requires `ssl_key_passphrase`" if @ssl_key && !@ssl_key_passphrase
        raise_config_error! "SSL key cannot be loaded from the specified #{@ssl_key} path. Please make the path readable." if @ssl_key && !File.readable?(@ssl_key)
        raise_config_error! "Specified SSL key #{@ssl_key} path cannot be writable for security reasons." if @ssl_key && File.writable?(@ssl_key)
      end

      if @keystore
        raise_config_error! "Using `keystore` requires `keystore_password`" unless @keystore_password
        raise_config_error! "Key(s) from the #{@keystore} path cannot be loaded. Please make the path readable." unless File.readable?(@keystore)
        raise_config_error! "Specified keystore #{@keystore} path cannot be writable for security reasons." if File.writable?(@keystore)
      end
    end # end of category
  end

  ##
  # @param message [String]
  # @raise [LogStash::ConfigurationError]
  def raise_config_error!(message)
    raise LogStash::ConfigurationError, message
  end

end