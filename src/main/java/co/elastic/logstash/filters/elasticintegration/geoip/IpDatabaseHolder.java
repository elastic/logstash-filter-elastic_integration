package co.elastic.logstash.filters.elasticintegration.geoip;

import org.elasticsearch.ingest.geoip.IpDatabase;

interface IpDatabaseHolder {
    boolean isValid();

    IpDatabaseAdapter getDatabase();

    String getTypeIdentifier();

    String info();
}
