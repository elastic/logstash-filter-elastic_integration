package co.elastic.logstash.filters.elasticintegration.geoip;

import org.elasticsearch.ingest.geoip.IpDatabase;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;

public class ConstantIpDatabaseHolder implements IpDatabaseHolder, Closeable {
    private final IpDatabaseAdapter ipDatabase;

    public ConstantIpDatabaseHolder(final IpDatabaseAdapter ipDatabase) {
        this.ipDatabase = Objects.requireNonNull(ipDatabase);
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public IpDatabase getDatabase() {
        return this.ipDatabase;
    }

    @Override
    public String getTypeIdentifier() {
        return this.ipDatabase.getDatabaseType();
    }

    @Override
    public String info() {
        return String.format("ConstantIpDatabaseHolder{type=%s}", getTypeIdentifier());
    }

    @Override
    public void close() throws IOException {
        this.ipDatabase.closeReader();
    }
}
