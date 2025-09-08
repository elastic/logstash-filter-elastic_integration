/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.ingest;

import org.elasticsearch.logstashbridge.core.IOUtilsBridge;
import org.elasticsearch.logstashbridge.ingest.ProcessorBridge;
import org.elasticsearch.logstashbridge.ingest.ProcessorFactoryBridge;
import org.elasticsearch.logstashbridge.ingest.ProcessorParametersBridge;
import org.elasticsearch.logstashbridge.plugins.IngestPluginBridge;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

public class SingleProcessorIngestPlugin implements IngestPluginBridge, Closeable {
    private final String type;
    private final ProcessorFactoryBridge processorFactory;

    public static Supplier<IngestPluginBridge> of(final String type, final Supplier<ProcessorFactoryBridge> factorySupplier) {
        return () -> new SingleProcessorIngestPlugin(type, factorySupplier.get());
    }

    public SingleProcessorIngestPlugin(String type, ProcessorFactoryBridge processorFactory) {
        this.type = type;
        this.processorFactory = processorFactory;
    }

    @Override
    public Map<String, ProcessorFactoryBridge> getProcessors(ProcessorParametersBridge parameters) {
        return Map.of(this.type, this.processorFactory);
    }

    @Override
    public void close() throws IOException {
        if (this.processorFactory instanceof Closeable) {
            IOUtilsBridge.closeWhileHandlingException((Closeable) this.processorFactory);
        }
    }
}
