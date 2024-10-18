/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.geoip;

import org.elasticsearch.ingest.geoip.GeoIpDatabase;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.db.CHMCache;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.db.NodeCache;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.DatabaseReader;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.model.AbstractResponse;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.model.AnonymousIpResponse;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.model.AsnResponse;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.model.CityResponse;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.model.ConnectionTypeResponse;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.model.CountryResponse;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.model.DomainResponse;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.model.EnterpriseResponse;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.model.IspResponse;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public class GeoIpDatabaseAdapter implements GeoIpDatabase, Closeable {
    private final DatabaseReader databaseReader;
    private final String databaseType;

    public GeoIpDatabaseAdapter(final DatabaseReader databaseReader) {
        this.databaseReader = databaseReader;
        this.databaseType = databaseReader.getMetadata().getDatabaseType();
    }

    @Override
    public String getDatabaseType() {
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

    @Override
    public AnonymousIpResponse getAnonymousIp(InetAddress ipAddress) {
        return getResponse(ipAddress, this.databaseReader::tryAnonymousIp);
    }

    @Override
    public EnterpriseResponse getEnterprise(InetAddress ipAddress) {
        return getResponse(ipAddress, this.databaseReader::tryEnterprise);
    }

    /* @Override // neither available nor reachable until Elasticsearch 8.15 */
    public ConnectionTypeResponse getConnectionType(InetAddress inetAddress) {
        return getResponse(inetAddress, this.databaseReader::tryConnectionType);
    }

    /* @Override // neither available nor reachable until Elasticsearch 8.15 */
    public DomainResponse getDomain(InetAddress ipAddress) {
        return getResponse(ipAddress, this.databaseReader::tryDomain);
    }

    /* @Override // neither available nor reachable until Elasticsearch 8.15 */
    public IspResponse getIsp(InetAddress ipAddress) {
        return getResponse(ipAddress, this.databaseReader::tryIsp);
    }

    private <T extends AbstractResponse> T getResponse(final InetAddress inetAddress, MaxmindTryLookup<T> resolver) {
        try {
            return resolver.lookup(inetAddress).orElse(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    public static GeoIpDatabaseAdapter defaultForPath(final Path database) throws IOException {
        return new Builder(database.toFile()).setCache(new CHMCache(10_000)).build();
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

        public GeoIpDatabaseAdapter build() throws IOException {
            final DatabaseReader.Builder readerBuilder = new DatabaseReader.Builder(this.databasePath);
            if (Objects.nonNull(this.nodeCache)) {
                readerBuilder.withCache(this.nodeCache);
            }
            return new GeoIpDatabaseAdapter(readerBuilder.build());
        }

    }
}
