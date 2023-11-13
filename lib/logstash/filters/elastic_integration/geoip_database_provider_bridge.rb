# encoding: utf-8

########################################################################
# Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
# under one or more contributor license agreements. Licensed under the
# Elastic License 2.0; you may not use this file except in compliance
# with the Elastic License 2.0.
########################################################################

require_relative "jar_dependencies"

##
# This module encapsulates methods and classes for bridging the gap between the
# Ruby-API [LogStash::GeoipDatabaseManager] and this plugin's native-Java
# GeoipDatabaseProvider.
module LogStash::Filters::ElasticIntegration::GeoipDatabaseProviderBridge

  GUIDANCE = "integrations that rely on the Geoip Processor will be unable to enrich events with geo data "\
             "unless you either provide your own databases with `geoip_database_directory` or run this pipeline "\
             "in a Logstash with Geoip Database Management enabled."

  def initialize_geoip_database_provider!
    java_import('co.elastic.logstash.filters.elasticintegration.geoip.GeoIpDatabaseProvider')
    @geoip_database_provider ||= GeoIpDatabaseProvider::Builder.new.tap do |builder|
      if geoip_database_directory
        logger.debug("discovering geoip databases from #{geoip_database_directory}")
        builder.discoverDatabases(java.io.File.new(geoip_database_directory))
      else
        geoip_database_manager = load_geoip_database_manager!
        if :UNAVAILABLE == geoip_database_manager
          logger.warn("Geoip Database Management is not available in the running version of Logstash; #{GUIDANCE}")
        elsif geoip_database_manager.enabled?
          logger.info "by not manually configuring self-managed databases with `geoip_database_directory => ...` "\
                        "you accept and agree to the MaxMind EULA, which allows Elastic Integrations to use Logstash's Geoip Database Management service. "\
                        "For more details please visit https://www.maxmind.com/en/geolite2/eula"

          geoip_database_manager.supported_database_types.each do |type|
            logger.debug("subscribing to managed geoip database #{type}")
            builder.setDatabaseHolder("GeoLite2-#{type}.mmdb", ObservingDatabaseHolder.new(type, eula_manager: geoip_database_manager, logger: logger))
          end
        elsif geoip_database_directory.nil?
          logger.warn("Geoip Database Management is disabled; #{GUIDANCE}")
        end
      end
    end.build
  end

  def load_geoip_database_manager!
    require 'geoip_database_management/manager'

    LogStash::GeoipDatabaseManagement::Manager.instance
  rescue LoadError
    :UNAVAILABLE
  end

  java_import('co.elastic.logstash.filters.elasticintegration.geoip.ManagedGeoipDatabaseHolder')
  class ObservingDatabaseHolder < ManagedGeoipDatabaseHolder
    def initialize(simple_database_type, eula_manager:, logger: nil)
      super("GeoLite2-#{simple_database_type}")

      @simple_database_type = simple_database_type
      @logger = logger

      @subscription = eula_manager.subscribe_database_path(simple_database_type)
      @subscription.observe(self)
    end

    def construct(db_info)
      @logger&.debug("CONSTRUCT[#{@simple_database_type} => #{db_info}]")
      self.setDatabasePath(db_info.path)
    end

    def on_update(db_info)
      @logger&.debug("ON_UPDATE[#{@simple_database_type} => #{db_info}]")
      self.setDatabasePath(db_info.path)
    end

    def on_expire()
      @logger&.debug("ON_EXPIRE[#{@simple_database_type}]")
      self.setDatabasePath(nil)
    end

    def close
      super
    ensure
      @subscription&.release!
    end

  end
end