package co.elastic.logstash.filters.elasticintegration.geoip;

import org.elasticsearch.ingest.Processor;
import org.elasticsearch.ingest.geoip.GeoIpProcessor;

import java.util.Map;

public class GeoIpProcessorFactory implements Processor.Factory {
    private final GeoIpDatabaseProvider geoIpDatabaseProvider;

    public GeoIpProcessorFactory(final GeoIpDatabaseProvider geoIpDatabaseProvider) {
        this.geoIpDatabaseProvider = geoIpDatabaseProvider;
    }

    @Override
    public Processor create(Map<String, Processor.Factory> processorFactories,
                            String tag,
                            String description,
                            Map<String, Object> config) throws Exception {
        return new GeoIpProcessor.Factory(this.geoIpDatabaseProvider).create(processorFactories, tag, description, config);
    }
}
