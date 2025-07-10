/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.ingest;

import org.elasticsearch.core.IOUtils;
import org.elasticsearch.logstashbridge.ingest.ProcessorBridge;
import org.elasticsearch.logstashbridge.plugins.IngestPluginBridge;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

public class SingleProcessorIngestPlugin implements IngestPluginBridge, Closeable {
    private final String type;
    private final ProcessorBridge.Factory processorFactory;

    public static Supplier<IngestPluginBridge> of(final String type, final Supplier<ProcessorBridge.Factory> factorySupplier) {
        return () -> new SingleProcessorIngestPlugin(type, factorySupplier.get());
    }

    public SingleProcessorIngestPlugin(String type, ProcessorBridge.Factory processorFactory) {
        this.type = type;
        this.processorFactory = processorFactory;
    }

    @Override
    public Map<String, ProcessorBridge.Factory> getProcessors(ProcessorBridge.Parameters parameters) {
        return Map.of(this.type, this.processorFactory);
    }

    @Override
    public void close() throws IOException {
        if (this.processorFactory instanceof Closeable) {
            IOUtils.closeWhileHandlingException((Closeable) this.processorFactory);
        }
    }
}
