package co.elastic.logstash.filters.elasticintegration.geoip;

import org.elasticsearch.ingest.geoip.shaded.com.maxmind.db.NodeCache;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.DatabaseReader;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.model.AbstractResponse;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.model.AsnResponse;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.model.CityResponse;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.model.CountryResponse;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Objects;
import java.util.Optional;

public class StaticGeoIpDatabase implements ValidatableGeoIpDatabase, Closeable {
    private final DatabaseReader databaseReader;
    private final String databaseType;

    public StaticGeoIpDatabase(final DatabaseReader databaseReader) {
        this.databaseReader = databaseReader;
        this.databaseType = databaseReader.getMetadata().getDatabaseType();
    }

    @Override
    public String getDatabaseType() throws IOException {
        return this.databaseType;
    }

    @Override
    public CityResponse getCity(InetAddress inetAddress) {
        return getResponse(inetAddress, this.databaseReader::tryCity);
    }

    @Override
    public CountryResponse getCountry(InetAddress inetAddress) {
        return getResponse(inetAddress, this.databaseReader::tryCountry);
    }

    @Override
    public AsnResponse getAsn(InetAddress inetAddress) {
        return getResponse(inetAddress, this.databaseReader::tryAsn);
    }

    private <T extends AbstractResponse> T getResponse(final InetAddress inetAddress, MaxmindTryLookup<T> resolver) {
        try {
            return resolver.lookup(inetAddress).orElse(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isValid() {
        return true;
    }

    interface MaxmindTryLookup<T extends AbstractResponse> {
        Optional<T> lookup(InetAddress inetAddress) throws Exception;
    }

    @Override
    public void release() throws IOException {
        // no-op: ES uses this internally to unload a database
        // from memory whenever there are zero processors that
        // hold a reference to it, but Logstash pipelines will
        // keep the database open until the pipeline is closed
    }

    @Override
    public void close() throws IOException {
        this.databaseReader.close();
    }

    public static class Builder {
        private File databasePath;
        private NodeCache nodeCache;

        public Builder(File databasePath) {
            this.databasePath = databasePath;
        }

        public Builder setCache(final NodeCache nodeCache) {
            this.nodeCache = nodeCache;
            return this;
        }

        public StaticGeoIpDatabase build() throws IOException {
            final DatabaseReader.Builder readerBuilder = new DatabaseReader.Builder(this.databasePath);
            if (Objects.nonNull(this.nodeCache)) {
                readerBuilder.withCache(this.nodeCache);
            }
            return new StaticGeoIpDatabase(readerBuilder.build());
        }

    }
}
