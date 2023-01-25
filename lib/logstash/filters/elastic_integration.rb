# encoding: utf-8
require "logstash/filters/base"
require "logstash/namespace"

class LogStash::Filters::ElasticIntegration < LogStash::Filters::Base

  config_name "elastic_integration"

  include(LogStash::PluginMixins::ElasticSearch::APIConfigs)

  def initialize
    super

    # this filter doesn't use codec
    if original_params.include?('codec')
      fail LogStash::ConfigurationError, 'The `elastic_integration` filter does not have an externally-configurable `codec`'
    end
  end

  def register
    logger.debug("Registering `filter-elastic_integration` plugin.")

    validate_ssl_settings!
    validate_authentication
    parse_user_password_from_cloud_auth(@cloud_auth) if @cloud_auth

  end # def register

  def filter(event)
    # bootstrap placeholder no-op
  end

  private
  ## Validates provided SSL options and
  # @raise [LogStash::ConfigurationError] if any options mismatch
  def validate_ssl_settings!
    if !@ssl
      ignored_params = original_params.keys.select { |param_key| param_key.start_with?('ssl_', 'tls_', 'keystore', 'cipher_suites', 'verify_mode') }
      @logger.warn("SSL-related config `#{ignored_params.join('`,`')}` will not be used because `ssl` is disabled") unless ignored_params.empty?
      return # code below assumes `ssl => true`
    end

    # IDENTITY-CENTRIC SETTINGS
    raise_config_error! "`ssl_certificate` or `keystore` must be configured when `ssl` is enabled" unless @ssl_certificate || @keystore
    raise_config_error! "`ssl_certificate` and `keystore` cannot both be configured" if @ssl_certificate && @keystore

    raise_config_error! "`ssl_key` is required when `ssl_certificate` is present" if @ssl_certificate && !@ssl_key
    raise_config_error! "`ssl_key` is not allowed unless `ssl_certificate` is provided" if @ssl_key && !@ssl_certificate
    raise_config_error! "`ssl_key_passphrase` is not allowed unless `ssl_key` is provided" if @ssl_key_passphrase && !@ssl_key

    raise_config_error! "`keystore_password` is required when `keystore` is present" if @keystore && !@keystore_password
    raise_config_error! "`keystore_password` is not allowed unless `keystore` is present" if @keystore_password && !@keystore

    # TRUST-CENTRIC SETTINGS
    raise_config_error! "`truststore_password` is required when `truststore` is present" if @truststore && !@truststore_password
    raise_config_error! "`truststore_password` is not allowed unless `truststore` is present" if @truststore_password && !@truststore

    if @ssl_verification_mode != "none"
      raise_config_error! "`ssl_certificate_authorities` and `truststore` cannot both be configured" if @ssl_certificate_authoritie&.any? && @truststore

      # if certificate authorities were NOT provided, but a keystore was, use it as a default trust store.
      if !@ssl_certificate_authorities&.any? && @keystore && @truststore.nil?
        @logger.warn("Using provided `keystore` as a default `truststore`")
        @truststore, @truststore_password = @keystore, @keystore_password
      end

      raise_config_error! "Using `ssl_verification_mode` set to `full` or `certificate` requires the configuration of trust with `ssl_certificate_authorities` or `truststore`" unless @ssl_certificate_authorities.any? || @truststore
    elsif @truststore
      raise_config_error! "The configuration of `truststore` requires setting `ssl_verification_mode` to `full` or `certificate`"
    elsif @ssl_certificate_authorities&.any?
      raise_config_error! "The configuration of `ssl_certificate_authorities` requires setting `ssl_verification_mode` to `full` or `certificate`"
    end
  end

  ## Validates available auth options and
  # @raise [LogStash::ConfigurationError] if any options mismatch
  def validate_authentication
    possible_auth_options = 0
    possible_auth_options += 1 if @cloud_auth
    possible_auth_options += 1 if (@api_key && @api_key.value)
    possible_auth_options += 1 if (@user || (@password && @password.value))

    if possible_auth_options > 1
      raise_config_error! "Multiple authentication options are specified, please only use one of user/password, cloud_auth or api_key"
    end

    if @api_key && @api_key.value && !effectively_ssl?
      raise_config_error! "Using api_key authentication requires SSL/TLS secured communication using the `ssl => true` option"
    end

    if @user && (!@password || !@password.value)
      raise_config_error! "Using basic authentication requires both user and password."
    end

    if @cloud_auth
      @user, @password = parse_user_password_from_cloud_auth(@cloud_auth)
      # params is the plugin global params hash which can be passed to ES client builder
      params['user'], params['password'] = @user, @password
    end
  end

  ## Makes sure to keep HTTPS when SSL is considered
  # @return [hosts] list of hosts contain HTTPS
  def effectively_ssl?
    return @ssl unless @ssl.nil?

    hosts = Array(@hosts)
    return false if hosts.nil? || hosts.empty?

    hosts.all? { |host| host && host.scheme == "https" }
  end

  ## Parses the cloud_id from the provided host uri
  # @raise [LogStash::ConfigurationError] if any exception occurs
  # @return [LogStash::Util::SafeURI] safe URI
  def parse_host_uri_from_cloud_id(cloud_id)
    begin # might not be available on older LS
      require 'logstash/util/cloud_setting_id'
    rescue LoadError
      raise raise_config_error! "The cloud_id setting is not supported by your version of Logstash, " +
        "please upgrade your installation (or set hosts instead)."
    end

    begin
      cloud_id = LogStash::Util::CloudSettingId.new(cloud_id) # already does append ":{port}' to host
    rescue ArgumentError => e
      raise_config_error! e.message.to_s.sub(/Cloud Id/i, 'cloud_id')
    end
    cloud_uri = "#{cloud_id.elasticsearch_scheme}://#{cloud_id.elasticsearch_host}"
    LogStash::Util::SafeURI.new(cloud_uri)
  end

  ## Parses the cloud auth credentials from the provided cloud_auth config
  # @raise [LogStash::ConfigurationError] if any exception occurs
  # @return [username, password] pair
  def parse_user_password_from_cloud_auth(cloud_auth)
    begin # might not be available on older LS
      require 'logstash/util/cloud_setting_auth'
    rescue LoadError
      raise raise_config_error! "The cloud_auth setting is not supported by your version of Logstash, " +
        "please upgrade your installation (or set user/password instead)."
    end

    cloud_auth = cloud_auth.value if cloud_auth.is_a?(LogStash::Util::Password)
    begin
      cloud_auth = LogStash::Util::CloudSettingAuth.new(cloud_auth)
    rescue ArgumentError => e
      raise raise_config_error! e.message.to_s.sub(/Cloud Auth/i, 'cloud_auth')
    end
    [ cloud_auth.username, cloud_auth.password ]
  end

  ##
  # @param message [String]
  # @raise [LogStash::ConfigurationError]
  def raise_config_error!(message)
    raise LogStash::ConfigurationError, message
  end

end