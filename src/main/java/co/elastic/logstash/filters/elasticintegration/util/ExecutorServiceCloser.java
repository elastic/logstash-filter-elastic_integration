package co.elastic.logstash.filters.elasticintegration.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class ExecutorServiceCloser implements Closeable {
    private static final Logger LOGGER = LogManager.getLogger(ExecutorServiceCloser.class);

    private final ExecutorService executorService;

    public ExecutorServiceCloser(final ExecutorService executorService) {
        this.executorService = executorService;
    }

    @Override
    public void close() throws IOException {
        LOGGER.info("Shutting down ExecutorService...");
        executorService.shutdown();
        final int limit = 30;
        if (!executorService.isTerminated()) {
            for (int attempt = 1; attempt <= limit; attempt++) {
                LOGGER.debug(String.format("waiting on previously-submitted work to finish (%s/%s)", attempt, limit));
                try {
                    if (executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                        LOGGER.info("ExecutorService has been shut down.");
                        return;
                    }
                } catch (InterruptedException e) {
                    LOGGER.warn("ExecutorService was interrupted while attempting to shut down gracefully");
                    throw new RuntimeException(e);
                }
            }
            LOGGER.warn("ExecutorService failed to shut down gracefully and will be forced to shut down");
            executorService.shutdownNow();
        }

    }
}
