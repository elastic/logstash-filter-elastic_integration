package co.elastic.logstash.filters.elasticintegration.geoip;

import org.elasticsearch.ingest.geoip.GeoIpDatabase;

public interface ValidatableGeoIpDatabase extends GeoIpDatabase {
    boolean isValid();
}
