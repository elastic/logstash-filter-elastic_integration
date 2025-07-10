/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.geoip;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.metadata.ProjectId;
import org.elasticsearch.ingest.geoip.IpDatabase;
import org.elasticsearch.logstashbridge.core.IOUtilsBridge;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class IpDatabaseProvider implements org.elasticsearch.ingest.geoip.IpDatabaseProvider, Closeable {

    private static final Logger LOGGER = LogManager.getLogger(IpDatabaseProvider.class);

    private final Map<String, IpDatabaseHolder> databaseMap;

    IpDatabaseProvider(Map<String, IpDatabaseHolder> databaseMap) {
        this.databaseMap = Map.copyOf(databaseMap);
    }

    @Override
    public Boolean isValid(ProjectId projectId, String databaseIdentifierFileName) {
        final IpDatabaseHolder holder = getDatabaseHolder(databaseIdentifierFileName);
        return Objects.nonNull(holder) && holder.isValid();
    }

    @Override
    public IpDatabase getDatabase(ProjectId projectId, String databaseIdentifierFileName) {
        final IpDatabaseHolder holder = getDatabaseHolder(databaseIdentifierFileName);
        if (Objects.isNull(holder)) {
            return null;
        } else {
            return holder.getDatabase();
        }
    }

    // visible for test
    IpDatabaseHolder getDatabaseHolder(String databaseIdentifierFileName) {
        return databaseMap.get(databaseIdentifierFileName);
    }

    @Override
    public void close() throws IOException {
        databaseMap.forEach((name, holder) -> {
            if (holder instanceof Closeable) {
                IOUtilsBridge.closeWhileHandlingException((Closeable) holder);
            }
        });
    }

    public static class Builder {
        private final Map<String, IpDatabaseHolder> databaseMap = new HashMap<>();

        public synchronized Builder setDatabaseHolder(final String identifierFileName, final IpDatabaseHolder holder) {
            final IpDatabaseHolder previous = databaseMap.put(identifierFileName, holder);
            if (Objects.nonNull(previous)) {
                LOGGER.warn(String.format("de-registered previous entry for `%s`: %s", identifierFileName, previous.info()));
                if (previous instanceof Closeable) {
                    IOUtilsBridge.closeWhileHandlingException((Closeable) previous);
                }
            }
            return this;
        }

        public Builder discoverDatabases(final File directory) throws IOException {
            //noinspection resource immediately consumed to list
            final List<Path> databases = Files.find(directory.toPath(), 3, Builder::isMaxMindDatabase).toList();

            if (databases.isEmpty()) {
                LOGGER.warn(String.format("Failed to find Maxmind DB files in `%s`", directory));
            } else {
                for (Path database : databases) {
                    try {
                        final IpDatabaseAdapter adapter = IpDatabaseAdapter.defaultForPath(database);
                        final IpDatabaseHolder holder = new ConstantIpDatabaseHolder(adapter);
                        this.setDatabaseHolder(database.getFileName().toString(), holder);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            return this;
        }

        private static boolean isMaxMindDatabase(final Path path, final BasicFileAttributes basicFileAttributes) {
            return basicFileAttributes.isRegularFile() && path.getFileName().toString().endsWith(".mmdb");
        }

        public final IpDatabaseProvider build() {
            return new IpDatabaseProvider(this.databaseMap);
        }
    }
}
