/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.ingest;

import org.elasticsearch.core.IOUtils;
import org.elasticsearch.ingest.Processor;
import org.elasticsearch.plugins.IngestPlugin;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

public class SingleProcessorIngestPlugin implements IngestPlugin, Closeable {
    private final String type;
    private final Processor.Factory processorFactory;

    public static Supplier<IngestPlugin> of(final String type, final Supplier<Processor.Factory> factorySupplier) {
        return () -> new SingleProcessorIngestPlugin(type, factorySupplier.get());
    }

    public SingleProcessorIngestPlugin(String type, Processor.Factory processorFactory) {
        this.type = type;
        this.processorFactory = processorFactory;
    }

    @Override
    public Map<String, Processor.Factory> getProcessors(Processor.Parameters parameters) {
        return Map.of(this.type, this.processorFactory);
    }

    @Override
    public void close() throws IOException {
        if (this.processorFactory instanceof Closeable) {
            IOUtils.closeWhileHandlingException((Closeable) this.processorFactory);
        }
    }
}
