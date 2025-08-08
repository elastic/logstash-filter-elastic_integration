/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.geoip;

import co.elastic.logstash.filters.elasticintegration.util.IngestDocumentUtil;
import co.elastic.logstash.filters.elasticintegration.util.ResourcesUtil;
import org.elasticsearch.logstashbridge.ingest.IngestDocumentBridge;
import org.elasticsearch.logstashbridge.ingest.ProcessorBridge;
import org.elasticsearch.logstashbridge.geoip.GeoIpProcessorBridge;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertAll;

class IpDatabaseProviderTest {

    private static final String EXAMPLE_DOT_COM_INET_ADDRESS = "93.184.216.34";

    @Test
    void loadTestVendoredDatabases() throws Exception {

        withVendoredGeoIpDatabaseProvider(geoIpDatabaseProvider -> {
            assertAll("Loaded databases all report valid",
                    () -> assertThat(geoIpDatabaseProvider.isValid("GeoLite2-ASN.mmdb"), is(true)),
                    () -> assertThat(geoIpDatabaseProvider.isValid("GeoLite2-City.mmdb"), is(true)),
                    () -> assertThat(geoIpDatabaseProvider.isValid("GeoLite2-Country.mmdb"), is(true)));

            assertAll("Non-loaded databases all report invalid",
                    () -> assertThat(geoIpDatabaseProvider.isValid("GeoLite2-Global.mmdb"), is(false)),
                    () -> assertThat(geoIpDatabaseProvider.isValid("Bananas.mmdb"), is(false)),
                    () -> assertThat(geoIpDatabaseProvider.isValid("Intergalactic.mmdb"), is(false)));
        });
    }

    @Test
    void basicASNLookup() throws Exception {
        withVendoredGeoIpDatabaseProvider(geoIpDatabaseProvider -> {
            withGeoipProcessor(geoIpDatabaseProvider, makeConfig("database_file", "GeoLite2-ASN.mmdb"), (processor) -> {
                final IngestDocumentBridge input = IngestDocumentUtil.createIngestDocument(Map.of("input", EXAMPLE_DOT_COM_INET_ADDRESS));

                final IngestDocumentBridge result = processIngestDocumentSynchronously(input, processor);

                assertAll(
                        () -> assertThat(result.getFieldValue("geoip.ip", String.class), is(equalTo(EXAMPLE_DOT_COM_INET_ADDRESS))),
                        () -> assertThat(result.getFieldValue("geoip.asn", Long.class), is(equalTo(15133L))),
                        () -> assertThat(result.getFieldValue("geoip.organization_name", String.class), containsString("MCI Communications Services")),
                        () -> assertThat(result.getFieldValue("geoip.network", String.class), is(equalTo("93.184.216.0/22")))
                );
            });
        });
    }

    @Test
    void basicCityDBLookupCityDetails() throws Exception {
        withVendoredGeoIpDatabaseProvider(geoIpDatabaseProvider -> {
            final Map<String, Object> configOverrides = Map.of("database_file", "GeoLite2-City.mmdb",
                    "properties", List.of("location", "timezone"));
            withGeoipProcessor(geoIpDatabaseProvider, makeConfig(configOverrides), (processor) -> {
                final IngestDocumentBridge input = IngestDocumentUtil.createIngestDocument(Map.of("input", EXAMPLE_DOT_COM_INET_ADDRESS));

                final IngestDocumentBridge result = processIngestDocumentSynchronously(input, processor);

                assertAll(
                        () -> assertThat(result.getFieldValue("geoip.location.lat", Double.class), is(closeTo(42.1596, 0.1))),
                        () -> assertThat(result.getFieldValue("geoip.location.lon", Double.class), is(closeTo(-70.8217, 0.1))),
                        () -> assertThat(result.getFieldValue("geoip.timezone", String.class), is(equalTo("America/New_York")))
                );
            });
        });
    }

    @Test
    void basicCityDBLookupCountryDetails() throws Exception {
        withVendoredGeoIpDatabaseProvider(ipDatabaseProvider -> {
            withGeoipProcessor(ipDatabaseProvider, makeConfig("database_file", "GeoLite2-City.mmdb"), (processor) -> {
                final IngestDocumentBridge input = IngestDocumentUtil.createIngestDocument(Map.of("input", EXAMPLE_DOT_COM_INET_ADDRESS));

                final IngestDocumentBridge result = processIngestDocumentSynchronously(input, processor);

                assertThat(result.getFieldValue("geoip.country_iso_code", String.class), is(equalTo("US")));
            });
        });
    }

    @Test
    void basicCountryDBLookupCountryDetails() throws Exception {
        withVendoredGeoIpDatabaseProvider(ipDatabaseProvider -> {
            withGeoipProcessor(ipDatabaseProvider, makeConfig("database_file", "GeoLite2-Country.mmdb"), (processor) -> {
                final IngestDocumentBridge input = IngestDocumentUtil.createIngestDocument(Map.of("input", EXAMPLE_DOT_COM_INET_ADDRESS));

                final IngestDocumentBridge result = processIngestDocumentSynchronously(input, processor);

                assertThat(result.getFieldValue("geoip.country_iso_code", String.class), is(equalTo("US")));
            });
        });
    }

    static IpDatabaseProvider loadVendoredGeoIpDatabases() throws IOException {
        final Path databases = ResourcesUtil.getResourcePath(IpDatabaseProviderTest.class, "databases").orElseThrow();
        return new IpDatabaseProvider.Builder().discoverDatabases(databases.toFile()).build();
    }

    static void withGeoIpDatabaseProvider(final SupplierWithIO<IpDatabaseProvider> geoIpDatabaseProviderSupplier,
                                          final ExceptionalConsumer<IpDatabaseProvider> geoIpDatabaseProviderConsumer) throws Exception {
        IpDatabaseProvider geoIpDatabaseProvider = geoIpDatabaseProviderSupplier.get();
        try (geoIpDatabaseProvider) {
            geoIpDatabaseProviderConsumer.accept(geoIpDatabaseProvider);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void withVendoredGeoIpDatabaseProvider(final ExceptionalConsumer<IpDatabaseProvider> geoIpDatabaseProviderConsumer) throws Exception {
        withGeoIpDatabaseProvider(IpDatabaseProviderTest::loadVendoredGeoIpDatabases, geoIpDatabaseProviderConsumer);
    }

    static void withGeoipProcessor(final IpDatabaseProvider geoIpDatabaseProvider, Map<String, Object> config, ExceptionalConsumer<ProcessorBridge> geoIpProcessorConsumer) throws Exception {
        final ProcessorBridge bridgeProcessor = ProcessorBridge.fromInternal(
                new GeoIpProcessorBridge.Factory("geoip", geoIpDatabaseProvider.toInternal()).toInternal()
                        .create(Map.of(), null, null, config, null));
        geoIpProcessorConsumer.accept(bridgeProcessor);
    }

    static IngestDocumentBridge processIngestDocumentSynchronously(final IngestDocumentBridge input, final ProcessorBridge processor) throws Exception {
        if (!processor.isAsync()) {
            return new IngestDocumentBridge(
                    processor.toInternal().execute(input.toInternal()).getSourceAndMetadata(),
                    processor.toInternal().execute(input.toInternal()).getIngestMetadata()
            );
        } else {
            final CompletableFuture<IngestDocumentBridge> future = new CompletableFuture<>();
            processor.execute(input, (id, ex) -> {
                if (ex != null) {
                    future.completeExceptionally(ex);
                } else {
                    future.complete(id);
                }
            });
            return future.get(10, TimeUnit.SECONDS);
        }
    }

    static final Map<String, Object> DEFAULT_CONFIG = Map.of(
            "field", "input"
    );

    static Map<String, Object> makeConfig(final Map<String, Object> config) {
        final Map<String, Object> merged = new HashMap<>(DEFAULT_CONFIG);
        merged.putAll(config);
        return merged;
    }

    static Map<String, Object> makeConfig(final String field, final String value) {
        return makeConfig(Map.of(field, value));
    }

    @FunctionalInterface
    public interface SupplierWithIO<V> {
        V get() throws IOException;
    }

    @FunctionalInterface
    public interface ExceptionalConsumer<T> {
        void accept(T t) throws Exception;
    }
}