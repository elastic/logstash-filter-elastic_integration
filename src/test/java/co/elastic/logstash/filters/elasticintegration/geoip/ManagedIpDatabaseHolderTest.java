package co.elastic.logstash.filters.elasticintegration.geoip;

import co.elastic.logstash.filters.elasticintegration.util.ResourcesUtil;
import org.apache.commons.io.FilenameUtils;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.*;


class ManagedIpDatabaseHolderTest {
    static final Path GEOLITE2_ASN_MMDB_PATH;
    static final Path GEOLITE2_CITY_MMDB_PATH;

    static final String GEOLITE2_ASN_TYPE = "GeoLite2-ASN.mmdb";
    static final String GEOLITE2_CITY_TYPE = "GeoLite2-City.mmdb";

    static {
        try {
            final Path databases = ResourcesUtil.getResourcePath(ManagedIpDatabaseHolderTest.class, "databases").orElseThrow();
            GEOLITE2_ASN_MMDB_PATH = databases.resolve(GEOLITE2_ASN_TYPE).toRealPath();
            GEOLITE2_CITY_MMDB_PATH = databases.resolve(GEOLITE2_CITY_TYPE).toRealPath();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    IpDatabaseProvider buildIpDatabaseProvider(final Path... paths) {
        final IpDatabaseProvider.Builder builder = new IpDatabaseProvider.Builder();
        for (Path path : paths) {
            final String databaseFileName = path.getFileName().toString();
            final String databaseTypeIdentifier = FilenameUtils.removeExtension(databaseFileName);
            @SuppressWarnings("resource") final ManagedIpDatabaseHolder dbh = new ManagedIpDatabaseHolder(databaseTypeIdentifier, path);
            builder.setDatabaseHolder(databaseFileName, dbh);
        }
        return builder.build();
    }

    @Test
    void releaseOnReload() throws Exception {
        try (IpDatabaseProvider ipDatabaseProvider = buildIpDatabaseProvider(GEOLITE2_CITY_MMDB_PATH)) {
            final IpDatabaseHolder cityIpDatabaseHolder = ipDatabaseProvider.getDatabaseHolder(GEOLITE2_CITY_TYPE);
            assert cityIpDatabaseHolder instanceof ManagedIpDatabaseHolder;

            final IpDatabaseAdapter first = cityIpDatabaseHolder.getDatabase();
            assertFalse(first.isReaderClosed());

            // update the holder's path. It's okay to be the same underlying file.
            ((ManagedIpDatabaseHolder) cityIpDatabaseHolder).setDatabasePath(GEOLITE2_CITY_MMDB_PATH.toString());

            // get the database again, and make sure we have a new open one, and that the old one has been closed
            final IpDatabaseAdapter second = cityIpDatabaseHolder.getDatabase();
            assertNotEquals(first, second);
            assertTrue(first.isReaderClosed());
            assertFalse(second.isReaderClosed());
        }
    }

    @Test
    void rejectTypeChangeOnReload() throws Exception {
        try (IpDatabaseProvider ipDatabaseProvider = buildIpDatabaseProvider(GEOLITE2_CITY_MMDB_PATH)) {
            final IpDatabaseHolder cityIpDatabaseHolder = ipDatabaseProvider.getDatabaseHolder(GEOLITE2_CITY_TYPE);
            assert cityIpDatabaseHolder instanceof ManagedIpDatabaseHolder;

            final IpDatabaseAdapter first = cityIpDatabaseHolder.getDatabase();
            assertFalse(first.isReaderClosed());

            // update the holder's path to the wrong type
            IllegalStateException illegalStateException = assertThrows(IllegalStateException.class, () -> {
                ((ManagedIpDatabaseHolder) cityIpDatabaseHolder).setDatabasePath(GEOLITE2_ASN_MMDB_PATH.toString());
            });
            assertThat(illegalStateException.getMessage(), Matchers.containsString("Incompatible database type `GeoLite2-ASN` (expected `GeoLite2-City`)"));
        }
    }
}