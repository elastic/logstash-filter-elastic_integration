package co.elastic.logstash.filters.elasticintegration.geoip;

import org.elasticsearch.ingest.geoip.GeoIpDatabase;

interface GeoipDatabaseHolder {
    boolean isValid();
    GeoIpDatabase getDatabase();
    String getTypeIdentifier();
    String info();
}
