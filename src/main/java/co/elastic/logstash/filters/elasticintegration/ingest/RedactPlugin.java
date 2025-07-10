/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
  
package co.elastic.logstash.filters.elasticintegration.ingest;

import org.elasticsearch.logstashbridge.ingest.ProcessorBridge;
import org.elasticsearch.logstashbridge.plugins.IngestPluginBridge;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.xpack.redact.RedactProcessor;

import java.util.Map;

public class RedactPlugin implements IngestPluginBridge {
    @Override
    public Map<String, ProcessorBridge.Factory> getProcessors(ProcessorBridge.Parameters parameters) {
        // Provide a TRIAL license state to the redact processor
        final XPackLicenseState trialLicenseState = new XPackLicenseState(parameters.unwrap().relativeTimeSupplier);

        return Map.of(RedactProcessor.TYPE, ProcessorBridge.Factory.wrap(new RedactProcessor.Factory(trialLicenseState, parameters.unwrap().matcherWatchdog)));
    }
}
