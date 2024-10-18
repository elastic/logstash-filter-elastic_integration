package co.elastic.logstash.filters.elasticintegration.geoip;

import org.elasticsearch.ingest.geoip.IpDatabase;

interface IpDatabaseHolder {
    boolean isValid();

    IpDatabase getDatabase();

    String getTypeIdentifier();

    String info();
}
