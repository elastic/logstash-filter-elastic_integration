package co.elastic.logstash.filters.elasticintegration.geoip;

import org.elasticsearch.core.IOUtils;
import org.elasticsearch.ingest.geoip.GeoIpDatabase;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;

public class ConstantGeoipDatabaseHolder implements GeoipDatabaseHolder, Closeable {
    private final GeoIpDatabaseAdapter geoipDatabase;

    public ConstantGeoipDatabaseHolder(final GeoIpDatabaseAdapter geoipDatabase) {
        this.geoipDatabase = Objects.requireNonNull(geoipDatabase);
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public GeoIpDatabase getDatabase() {
        return this.geoipDatabase;
    }

    @Override
    public String getTypeIdentifier() {
        return this.geoipDatabase.getDatabaseType();
    }

    @Override
    public String info() {
        return String.format("ConstantGeoipDatabase{type=%s}", getTypeIdentifier());
    }

    @Override
    public void close() throws IOException {
        IOUtils.closeWhileHandlingException(this.geoipDatabase);
    }
}
