
require 'logstash/plugin_mixins/ca_trusted_fingerprint_support'

module LogStash; module PluginMixins; module ElasticSearch
  module APIConfigs

    # This module defines common options that can be reused by alternate elasticsearch output plugins such as the elasticsearch_data_streams output.

    DEFAULT_HOST = ::LogStash::Util::SafeURI.new("//127.0.0.1")

    CONFIG_PARAMS = {
        # Username to authenticate to a secure Elasticsearch cluster
        :user => { :validate => :string },

        # Password to authenticate to a secure Elasticsearch cluster
        :password => { :validate => :password },

        # Authenticate using Elasticsearch API key.
        # format is id:api_key (as returned by https://www.elastic.co/guide/en/elasticsearch/reference/current/security-api-create-api-key.html[Create API key])
        :api_key => { :validate => :password },

        # Cloud authentication string ("<username>:<password>" format) is an alternative for the `user`/`password` configuration.
        #
        # For more details, check out the https://www.elastic.co/guide/en/logstash/current/connecting-to-cloud.html#_cloud_auth[cloud documentation]
        :cloud_auth => { :validate => :password },

        # HTTP Path at which the Elasticsearch server lives. Use this if you must run Elasticsearch behind a proxy that remaps
        # the root path for the Elasticsearch HTTP API lives.
        # Note that if you use paths as components of URLs in the 'hosts' field you may
        # not also set this field. That will raise an error at startup
        :path => { :validate => :string },

        # Enable SSL/TLS secured communication to Elasticsearch cluster. Leaving this unspecified will use whatever scheme
        # is specified in the URLs listed in 'hosts'. If no explicit protocol is specified plain HTTP will be used.
        # If SSL is explicitly disabled here the plugin will refuse to start if an HTTPS URL is given in 'hosts'
        :ssl => { :validate => :boolean, :default => true },

        # a path for SSL certificate which will be used when SSL is enabled
        :ssl_certificate => { :validate => :path },

        # a path for SSL certificate key, this may be used when SSL is enabled and SSL path is provided
        :ssl_key => { :validate => :path },

        # SSL keyphrase, this may be used when SSL is enabled and SSL path is provided
        :ssl_key_passphrase => { :validate => :password },

        # Option to validate the server's certificate. Disabling this severely compromises security.
        # For more information on disabling certificate verification please read
        # https://www.cs.utexas.edu/~shmat/shmat_ccs12.pdf
        :ssl_certificate_verification => { :validate => :boolean, :default => true },

        # list of paths for SSL certificate authorities
        :ssl_certificate_authorities => { :validate => :path, :list => true },

        # The JKS truststore to validate the server's certificate.
        # Use either `:truststore` or `:cacert`
        :truststore => { :validate => :path },

        # Set the truststore password
        :truststore_password => { :validate => :password },

        # The keystore used to present a certificate to the server.
        # It can be either .jks or .p12
        :keystore => { :validate => :path },

        # Set the keystore password
        :keystore_password => { :validate => :password },

        # This option needs to be used with `ssl_certificate_authorities` and a defined list of CAs.
        :ssl_verification_mode => { :validate => ["none", "full", "certificate"], :default => "full" },

        # One or more hex-encoded SHA256 fingerprints to trust as Certificate Authorities
        :ca_trusted_fingerprint => LogStash::PluginMixins::CATrustedFingerprintSupport,

        # ssl-expert-mode
        :ssl_cipher_suites => { :validate => :string, :list => true },
        :ssl_supported_protocols => { :validate => string, :list => true },

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
        :hosts => { :validate => :uri, :default => [ DEFAULT_HOST ], :list => true },

        # Cloud ID, from the Elastic Cloud web console. If set `hosts` should not be used.
        #
        # For more details, check out the https://www.elastic.co/guide/en/logstash/current/connecting-to-cloud.html#_cloud_id[cloud documentation]
        :cloud_id => { :validate => :string }

    }.freeze

    def self.included(base)
      CONFIG_PARAMS.each do |name, opts|
        if opts.kind_of?(Module)
          base.include(opts)
        else
          base.config(name, opts)
        end
      end
    end

  end
end; end; end
