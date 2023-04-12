/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.geoip;


import co.elastic.logstash.filters.elasticintegration.util.ResourcesUtil;
import com.google.common.net.InetAddresses;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.model.AsnResponse;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.model.CityResponse;
import org.elasticsearch.ingest.geoip.shaded.com.maxmind.geoip2.model.CountryResponse;
import org.elasticsearch.ingest.geoip.GeoIpDatabase;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.function.Consumer;

import static com.google.common.net.InetAddresses.toAddrString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

class GeoIpDatabaseProviderTest {

    private static final InetAddress EXAMPLE_DOT_COM_INET_ADDRESS = InetAddresses.forString("93.184.216.34");

    @Test
    void loadTestVendoredDatabases() throws IOException {
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
    void basicASNLookup() throws IOException {
        withVendoredGeoIpDatabaseProvider(geoIpDatabaseProvider -> {
            final AsnResponse asn = geoIpDatabaseProvider.getDatabase("GeoLite2-ASN.mmdb").getAsn(EXAMPLE_DOT_COM_INET_ADDRESS);
            assertThat(asn, is(notNullValue()));
            assertThat(asn.getIpAddress(), is(equalTo(toAddrString(EXAMPLE_DOT_COM_INET_ADDRESS))));
            assertThat(asn.getAutonomousSystemNumber(), is(equalTo(15133L)));
            assertThat(asn.getAutonomousSystemOrganization(), containsString("MCI Communications Services"));
            assertThat(asn.getNetwork().toString(), is(equalTo("93.184.216.0/22")));
        });
    }

    @Test
    void basicCityDBLookupCityDetails() throws IOException {
        withVendoredGeoIpDatabaseProvider(geoIpDatabaseProvider -> {
            final GeoIpDatabase cityDatabase = geoIpDatabaseProvider.getDatabase("GeoLite2-City.mmdb");
            final CityResponse city = cityDatabase.getCity(EXAMPLE_DOT_COM_INET_ADDRESS);

            assertThat(city, is(notNullValue()));
            assertThat(city.getLocation().getLatitude(), is(closeTo(42.1596, 0.1)));
            assertThat(city.getLocation().getLongitude(), is(closeTo(-70.8217, 0.1)));
            assertThat(city.getLocation().getTimeZone(), is(equalTo("America/New_York")));

            assertThat(city.getCountry().getIsoCode(), is(equalTo("US")));
        });
    }

    @Test
    void basicCityDBLookupCountryDetails() throws IOException {
        withVendoredGeoIpDatabaseProvider(geoIpDatabaseProvider -> {
            final GeoIpDatabase cityDatabase = geoIpDatabaseProvider.getDatabase("GeoLite2-City.mmdb");
            final CountryResponse country = cityDatabase.getCountry(EXAMPLE_DOT_COM_INET_ADDRESS);

            assertThat(country, is(notNullValue()));
            assertThat(country.getCountry().getIsoCode(), is(equalTo("US")));
        });
    }

    @Test
    void basicCountryDBLookupCountryDetails() throws IOException {
        withVendoredGeoIpDatabaseProvider(geoIpDatabaseProvider -> {
            final GeoIpDatabase countryDatabase = geoIpDatabaseProvider.getDatabase("GeoLite2-Country.mmdb");
            final CountryResponse country = countryDatabase.getCountry(EXAMPLE_DOT_COM_INET_ADDRESS);

            assertThat(country, is(notNullValue()));
            assertThat(country.getCountry().getIsoCode(), is(equalTo("US")));
        });
    }

    static GeoIpDatabaseProvider loadVendoredGeoIpDatabases() throws IOException {
        final Path databases = ResourcesUtil.getResourcePath(GeoIpDatabaseProviderTest.class, "databases").orElseThrow();
        return new GeoIpDatabaseProvider.Builder().setDatabases(databases.toFile()).build();
    }

    static void withGeoIpDatabaseProvider(final SupplierWithIO<GeoIpDatabaseProvider> geoIpDatabaseProviderSupplier,
                                          final Consumer<GeoIpDatabaseProvider> geoIpDatabaseProviderConsumer) throws IOException {
        GeoIpDatabaseProvider geoIpDatabaseProvider = geoIpDatabaseProviderSupplier.get();
        try (geoIpDatabaseProvider) {
            geoIpDatabaseProviderConsumer.accept(geoIpDatabaseProvider);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static void withVendoredGeoIpDatabaseProvider(final Consumer<GeoIpDatabaseProvider> geoIpDatabaseProviderConsumer) throws IOException {
        withGeoIpDatabaseProvider(GeoIpDatabaseProviderTest::loadVendoredGeoIpDatabases, geoIpDatabaseProviderConsumer);
    }

    @FunctionalInterface
    public interface SupplierWithIO<V> {
        V get() throws IOException;
    }
}