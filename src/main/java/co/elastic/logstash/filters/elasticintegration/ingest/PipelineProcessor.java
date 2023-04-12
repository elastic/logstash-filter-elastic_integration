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
import org.elasticsearch.ingest.AbstractProcessor;
import org.elasticsearch.ingest.ConfigurationUtils;
import org.elasticsearch.ingest.IngestDocument;
import org.elasticsearch.ingest.Processor;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.TemplateScript;

import java.util.Map;
import java.util.function.BiConsumer;

public class PipelineProcessor extends AbstractProcessor {
    public static final String TYPE = "pipeline";

    private final String pipelineName;

    private final TemplateScript.Factory pipelineTemplate;
    private final IngestPipelineResolver pipelineProvider;
    private final boolean ignoreMissingPipeline;

    private static final Logger LOGGER = LogManager.getLogger(PipelineProcessor.class);

    private PipelineProcessor(String tag,
                              String description,
                              TemplateScript.Factory pipelineTemplate,
                              String pipelineName,
                              boolean ignoreMissingPipeline,
                              IngestPipelineResolver pipelineProvider) {
        super(tag, description);
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
    public boolean isAsync() {
        // the pipeline processor always presents itself as async
        return true;
    }

    @Override
    public void execute(IngestDocument ingestDocument, BiConsumer<IngestDocument, Exception> handler) {
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

    public static class Factory implements Processor.Factory {

        private final IngestPipelineResolver pipelineProvider;
        private final ScriptService scriptService;

        public Factory(IngestPipelineResolver pipelineProvider, ScriptService scriptService) {
            this.pipelineProvider = pipelineProvider;
            this.scriptService = scriptService;
        }

        @Override
        public Processor create(Map<String, Processor.Factory> registry,
                                String processorTag,
                                String description,
                                Map<String, Object> config) throws Exception {
            String pipeline = ConfigurationUtils.readStringProperty(TYPE, processorTag, config, "name");
            TemplateScript.Factory pipelineTemplate = ConfigurationUtils.compileTemplate(TYPE, processorTag, "name", pipeline, scriptService);
            boolean ignoreMissingPipeline = ConfigurationUtils.readBooleanProperty(TYPE, processorTag, config, "ignore_missing_pipeline", false);
            return new PipelineProcessor(processorTag, description, pipelineTemplate, pipeline, ignoreMissingPipeline, pipelineProvider);
        }
    }
}
