/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.ingest;

import co.elastic.logstash.filters.elasticintegration.IngestPipeline;
import co.elastic.logstash.filters.elasticintegration.IngestPipelineResolver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ingest.Processor;
import org.elasticsearch.logstashbridge.ingest.ConfigurationUtilsBridge;
import org.elasticsearch.logstashbridge.ingest.IngestDocumentBridge;
import org.elasticsearch.logstashbridge.ingest.ProcessorBridge;
import org.elasticsearch.logstashbridge.script.ScriptServiceBridge;
import org.elasticsearch.logstashbridge.script.TemplateScriptBridge;

import java.util.Map;
import java.util.function.BiConsumer;

public class PipelineProcessor implements ProcessorBridge {
    public static final String TYPE = "pipeline";

    private final String tag;
    private final String description;
    private final String pipelineName;

    private final TemplateScriptBridge.Factory pipelineTemplate;
    private final IngestPipelineResolver pipelineProvider;
    private final boolean ignoreMissingPipeline;

    private static final Logger LOGGER = LogManager.getLogger(PipelineProcessor.class);

    private PipelineProcessor(String tag,
                              String description,
                              TemplateScriptBridge.Factory pipelineTemplate,
                              String pipelineName,
                              boolean ignoreMissingPipeline,
                              IngestPipelineResolver pipelineProvider) {
        this.tag = tag;
        this.description = description;
        this.pipelineTemplate = pipelineTemplate;
        this.pipelineName = pipelineName;
        this.pipelineProvider = pipelineProvider;
        this.ignoreMissingPipeline = ignoreMissingPipeline;
    }

    public String getPipelineName() {
        return this.pipelineName;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getTag() {
        return this.tag;
    }

    @Override
    public String getDescription() {
        return this.description;
    }

    @Override
    public boolean isAsync() {
        // the pipeline processor always presents itself as async
        return true;
    }

    @Override
    public void execute(IngestDocumentBridge ingestDocument, BiConsumer<IngestDocumentBridge, Exception> handler) {
        String pipelineName = ingestDocument.renderTemplate(this.pipelineTemplate);
        IngestPipeline pipeline = pipelineProvider.resolve(pipelineName).orElse(null);
        if (pipeline != null) {
            pipeline.execute(ingestDocument, handler);
        } else {
            if (ignoreMissingPipeline) {
                handler.accept(ingestDocument, null);
            } else {
                handler.accept(
                        null,
                        new IllegalStateException("Pipeline processor configured for non-existent pipeline [" + pipelineName + ']')
                );
            }
        }
    }

    // TODO: find a way to remove this method
    //  it is due to StableBridgeAPI#unwrap() requirement
    @Override
    public Processor unwrap() {
        throw new RuntimeException("Unallowed operation.");
    }

    public static class Factory implements ProcessorBridge.Factory {

        private final IngestPipelineResolver pipelineProvider;
        private final ScriptServiceBridge scriptService;

        public Factory(IngestPipelineResolver pipelineProvider, ScriptServiceBridge scriptService) {
            this.pipelineProvider = pipelineProvider;
            this.scriptService = scriptService;
        }

        @Override
        public ProcessorBridge create(Map<String, ProcessorBridge.Factory> registry,
                                String processorTag,
                                String description,
                                Map<String, Object> config) throws Exception {
            String pipeline = ConfigurationUtilsBridge.readStringProperty(TYPE, processorTag, config, "name");
            TemplateScriptBridge.Factory pipelineTemplate = ConfigurationUtilsBridge.compileTemplate(TYPE, processorTag, "name", pipeline, scriptService);
            boolean ignoreMissingPipeline = ConfigurationUtilsBridge.readBooleanProperty(TYPE, processorTag, config, "ignore_missing_pipeline", false);
            return new PipelineProcessor(processorTag, description, pipelineTemplate, pipeline, ignoreMissingPipeline, pipelineProvider);
        }
    }
}
