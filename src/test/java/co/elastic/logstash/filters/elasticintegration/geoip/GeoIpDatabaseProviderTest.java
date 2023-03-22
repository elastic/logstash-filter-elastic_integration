package co.elastic.logstash.filters.elasticintegration.geoip;


import co.elastic.logstash.filters.elasticintegration.util.ResourcesUtil;
import com.google.common.net.InetAddresses;
import com.maxmind.geoip2.model.AsnResponse;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.model.CountryResponse;
import org.elasticsearch.ingest.geoip.GeoIpDatabase;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;

import static com.google.common.net.InetAddresses.toAddrString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

class GeoIpDatabaseProviderTest {
//
    private static final GeoIpDatabaseProvider GEO_IP_DATABASE_PROVIDER = loadVendoredGeoIpDatabases();

    private static final InetAddress EXAMPLE_DOT_COM_INET_ADDRESS = InetAddresses.forString("93.184.216.34");

    @Test
    void loadTestVendoredDatabases() {
        assertAll("Loaded databases all report valid",
                () -> assertThat(GEO_IP_DATABASE_PROVIDER.isValid("GeoLite2-ASN.mmdb"), is(true)),
                () -> assertThat(GEO_IP_DATABASE_PROVIDER.isValid("GeoLite2-City.mmdb"), is(true)),
                () -> assertThat(GEO_IP_DATABASE_PROVIDER.isValid("GeoLite2-Country.mmdb"), is(true)));

        assertAll("Non-loaded databases all report invalid",
                () -> assertThat(GEO_IP_DATABASE_PROVIDER.isValid("GeoLite2-Global.mmdb"), is(false)),
                () -> assertThat(GEO_IP_DATABASE_PROVIDER.isValid("Bananas.mmdb"), is(false)),
                () -> assertThat(GEO_IP_DATABASE_PROVIDER.isValid("Intergalactic.mmdb"), is(false)));
    }

    @Test
    void basicASNLookup() {
        final AsnResponse asn = GEO_IP_DATABASE_PROVIDER.getDatabase("GeoLite2-ASN.mmdb").getAsn(EXAMPLE_DOT_COM_INET_ADDRESS);
        assertThat(asn, is(notNullValue()));
        assertThat(asn.getIpAddress(), is(equalTo(toAddrString(EXAMPLE_DOT_COM_INET_ADDRESS))));
        assertThat(asn.getAutonomousSystemNumber(), is(equalTo(15133L)));
        assertThat(asn.getAutonomousSystemOrganization(), containsString("MCI Communications Services"));
        assertThat(asn.getNetwork().toString(), is(equalTo("93.184.216.0/22")));
    }

    @Test
    void basicCityDBLookupCityDetails() {
        final GeoIpDatabase cityDatabase = GEO_IP_DATABASE_PROVIDER.getDatabase("GeoLite2-City.mmdb");
        final CityResponse city = cityDatabase.getCity(EXAMPLE_DOT_COM_INET_ADDRESS);

        assertThat(city, is(notNullValue()));
        assertThat(city.getLocation().getLatitude(), is(closeTo(42.1596, 0.1)));
        assertThat(city.getLocation().getLongitude(), is(closeTo(-70.8217, 0.1)));
        assertThat(city.getLocation().getTimeZone(), is(equalTo("America/New_York")));

        assertThat(city.getCountry().getIsoCode(), is(equalTo("US")));
    }

    @Test
    void basicCityDBLookupCountryDetails() {
        final GeoIpDatabase cityDatabase = GEO_IP_DATABASE_PROVIDER.getDatabase("GeoLite2-City.mmdb");
        final CountryResponse country = cityDatabase.getCountry(EXAMPLE_DOT_COM_INET_ADDRESS);

        assertThat(country, is(notNullValue()));
        assertThat(country.getCountry().getIsoCode(), is(equalTo("US")));
    }

    @Test
    void basicCountryDBLookupCountryDetails() {
        final GeoIpDatabase countryDatabase = GEO_IP_DATABASE_PROVIDER.getDatabase("GeoLite2-Country.mmdb");
        final CountryResponse country = countryDatabase.getCountry(EXAMPLE_DOT_COM_INET_ADDRESS);

        assertThat(country, is(notNullValue()));
        assertThat(country.getCountry().getIsoCode(), is(equalTo("US")));
    }

    static GeoIpDatabaseProvider loadVendoredGeoIpDatabases() {
        final Path databases = ResourcesUtil.getResourcePath(GeoIpDatabaseProviderTest.class, "databases").orElseThrow();
        try {
            return new GeoIpDatabaseProvider.Builder().setDatabases(databases.toFile()).build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}