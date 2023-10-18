package co.elastic.logstash.filters.elasticintegration.ingest;

import org.elasticsearch.ingest.Processor;
import org.elasticsearch.plugins.IngestPlugin;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.xpack.redact.RedactProcessor;

import java.util.Map;

public class RedactPlugin extends Plugin implements IngestPlugin {
    @Override
    public Map<String, Processor.Factory> getProcessors(Processor.Parameters parameters) {
        // Provide a TRIAL license state to the redact processor
        final XPackLicenseState trialLicenseState = new XPackLicenseState(parameters.relativeTimeSupplier);

        return Map.of(RedactProcessor.TYPE, new RedactProcessor.Factory(trialLicenseState, parameters.matcherWatchdog));
    }
}
