package co.elastic.logstash.filters.elasticintegration.geoip;

import org.elasticsearch.ingest.geoip.shaded.com.maxmind.db.CHMCache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.ingest.geoip.GeoIpDatabase;

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

public class GeoIpDatabaseProvider implements org.elasticsearch.ingest.geoip.GeoIpDatabaseProvider, Closeable {

    private static final Logger LOGGER = LogManager.getLogger(GeoIpDatabaseProvider.class);

    private final Map<String, ValidatableGeoIpDatabase> databaseMap;

    GeoIpDatabaseProvider(Map<String, ValidatableGeoIpDatabase> databaseMap) {
        this.databaseMap = Map.copyOf(databaseMap);
    }

    @Override
    public Boolean isValid(String databaseIdentifierFileName) {
        final ValidatableGeoIpDatabase database = databaseMap.get(databaseIdentifierFileName);
        return Objects.nonNull(database) && database.isValid();
    }

    @Override
    public GeoIpDatabase getDatabase(String databaseIdentifierFileName) {
        return databaseMap.get(databaseIdentifierFileName);
    }

    @Override
    public void close() throws IOException {
        databaseMap.forEach((name, database) -> {
            if (database instanceof Closeable) {
                IOUtils.closeWhileHandlingException((Closeable) database);
            }
        });
    }

    public static class Builder {
        private final Map<String, ValidatableGeoIpDatabase> databaseMap = new HashMap<>();

        public synchronized Builder setDatabase(final String identifierFileName, final ValidatableGeoIpDatabase database) {
            final ValidatableGeoIpDatabase previous = databaseMap.put(identifierFileName, database);
            if (Objects.nonNull(previous)) {
                LOGGER.warn(String.format("de-registered previous entry for `%s`: %s", identifierFileName, previous));
                if (previous instanceof Closeable) {
                    IOUtils.closeWhileHandlingException((Closeable) previous);
                }
            }
            return this;
        }

        public Builder setDatabases(final File directory) throws IOException {
            //noinspection resource immediately consumed to list
            final List<Path> databases = Files.find(directory.toPath(), 3, Builder::isMaxMindDatabase).toList();

            if (databases.isEmpty()) {
                LOGGER.warn(String.format("Failed to find Maxmind DB files in `%s`", directory));
            } else {
                for (Path database : databases) {
                    try {
                        this.setDatabase(database.getFileName().toString(),
                                new StaticGeoIpDatabase.Builder(database.toFile()).setCache(new CHMCache(10_000)).build());
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

        final GeoIpDatabaseProvider build() {
            return new GeoIpDatabaseProvider(this.databaseMap);
        }
    }
}
