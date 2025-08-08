package co.elastic.logstash.filters.elasticintegration.geoip;

interface IpDatabaseHolder {
    boolean isValid();

    IpDatabaseAdapter getDatabase();

    String getTypeIdentifier();

    String info();
}
