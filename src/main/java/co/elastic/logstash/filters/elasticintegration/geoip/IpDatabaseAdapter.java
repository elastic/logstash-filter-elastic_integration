/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.geoip;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.logstashbridge.core.CheckedBiFunctionBridge;
import org.elasticsearch.logstashbridge.geoip.AbstractExternalIpDatabaseBridge;
import org.elasticsearch.logstashbridge.geoip.IpDatabaseBridge;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.db.CHMCache;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.db.NoCache;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.db.NodeCache;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.db.Reader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class IpDatabaseAdapter extends AbstractExternalIpDatabaseBridge {
    private static final Logger LOGGER = LogManager.getLogger(IpDatabaseAdapter.class);

    private final Reader databaseReader;
    private final String databaseType;

    private volatile boolean isReaderClosed = false;

    public IpDatabaseAdapter(final Reader databaseReader) {
        this.databaseReader = databaseReader;
        this.databaseType = databaseReader.getMetadata().getDatabaseType();
    }

    @Override
    public String getDatabaseType() {
        return this.databaseType;
    }

    @Override
    public <RESPONSE> RESPONSE getResponse(String ipAddress, CheckedBiFunctionBridge<Reader, String, RESPONSE, Exception> responseProvider) {
        try {
            return responseProvider.apply(this.databaseReader, ipAddress);
        } catch (Exception e) {
            throw convertToRuntime(e);
        }
    }

    private static RuntimeException convertToRuntime(final Exception e) {
        if (e instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException(e);
    }

    @Override
    public void close() throws IOException {
        // no-op
        // IpDatabase is an AutoCloseable class which is not our intention to close after try-with-resource operations.
        // use closeReader() instead
    }

    public void closeReader() throws IOException {
        LOGGER.debug("Closing the database adapter");
        this.databaseReader.close();
        this.isReaderClosed = true;
    }

    // visible for test
    boolean isReaderClosed() {
        return this.isReaderClosed;
    }

    public static IpDatabaseAdapter defaultForPath(final Path database) throws IOException {
        return new Builder(database.toFile()).setCache(new CHMCache(10_000)).build();
    }

    public static class Builder {
        private File databasePath;
        private NodeCache nodeCache;

        public Builder(final File databasePath) {
            this.databasePath = databasePath;
        }

        public Builder setCache(final NodeCache nodeCache) {
            this.nodeCache = nodeCache;
            return this;
        }

        public IpDatabaseAdapter build() throws IOException {
            final NodeCache nodeCache = Optional.ofNullable(this.nodeCache).orElseGet(NoCache::getInstance);
            final Reader databaseReader = new Reader(this.databasePath, nodeCache);
            return new IpDatabaseAdapter(databaseReader);
        }
    }
}