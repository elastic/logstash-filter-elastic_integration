package co.elastic.logstash.filters.elasticintegration.geoip;

public interface IpDatabaseHolder {
    boolean isValid();

    IpDatabaseAdapter getDatabase();

    String getTypeIdentifier();

    String info();
}
