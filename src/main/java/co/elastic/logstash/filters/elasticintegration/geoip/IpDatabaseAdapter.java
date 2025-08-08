/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.geoip;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.logstashbridge.geoip.IpDatabaseBridge;
import org.elasticsearch.logstashbridge.geoip.MaxMindDbBridge;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class IpDatabaseAdapter extends IpDatabaseBridge.AbstractExternal {
    private static final Logger LOGGER = LogManager.getLogger(IpDatabaseAdapter.class);

    private final MaxMindDbBridge.Reader databaseReader;
    private final String databaseType;

    private volatile boolean isReaderClosed = false;

    public IpDatabaseAdapter(final MaxMindDbBridge.Reader databaseReader) {
        this.databaseReader = databaseReader;
        this.databaseType = databaseReader.getDatabaseType();
    }

    @Override
    public String getDatabaseType() {
        return this.databaseType;
    }

    @Override
    public MaxMindDbBridge.Reader getDatabaseReader() throws IOException {
        return this.databaseReader;
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
        return new Builder(database.toFile()).setCache(MaxMindDbBridge.NodeCache.get(10_000)).build();
    }

    public static class Builder {
        private File databasePath;
        private MaxMindDbBridge.NodeCache nodeCache;

        public Builder(final File databasePath) {
            this.databasePath = databasePath;
        }

        public Builder setCache(final MaxMindDbBridge.NodeCache nodeCache) {
            this.nodeCache = nodeCache;
            return this;
        }

        public IpDatabaseAdapter build() throws IOException {
            final MaxMindDbBridge.NodeCache nodeCache = Optional.ofNullable(this.nodeCache).orElseGet(MaxMindDbBridge.NodeCache::getInstance);
            final MaxMindDbBridge.Reader databaseReader = new MaxMindDbBridge.Reader(this.databasePath, nodeCache);
            return new IpDatabaseAdapter(databaseReader);
        }
    }
}