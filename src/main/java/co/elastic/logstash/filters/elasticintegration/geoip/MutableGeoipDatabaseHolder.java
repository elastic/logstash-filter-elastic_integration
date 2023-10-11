package co.elastic.logstash.filters.elasticintegration.geoip;

import org.elasticsearch.core.IOUtils;
import org.elasticsearch.ingest.geoip.GeoIpDatabase;

import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;

public class SubscribedGeoipDatabaseHolder implements GeoipDatabaseHolder, Closeable {

    private ValidatableGeoIpDatabase currentDatabase;

    private final Function<String,ValidatableGeoIpDatabase> databaseInitializer;

    private final ReentrantReadWriteLock.ReadLock readLock;
    private final ReentrantReadWriteLock.WriteLock writeLock;
    {
        final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
        readLock = readWriteLock.readLock();
        writeLock = readWriteLock.writeLock();
    }

    public SubscribedGeoipDatabaseHolder(Function<String, ValidatableGeoIpDatabase> databaseInitializer) {
        this.databaseInitializer = databaseInitializer;
    }

    @Override
    public boolean isValid() {
        return withLock(readLock, () -> Objects.nonNull(this.currentDatabase));
    }

    @Override
    public GeoIpDatabase getDatabase() {
        return withLock(readLock, () -> this.currentDatabase);
    }

    public void setDatabasePath(final String newDatabasePath) {
        withLock(writeLock, () -> {
            this.currentDatabase = Optional.ofNullable(newDatabasePath)
                    .map(this.databaseInitializer)
                    .orElse(null);
        });
    }

    @Override
    public void close() throws IOException {
        withLock(writeLock, () -> {
            if (Objects.nonNull(this.currentDatabase) && this.currentDatabase instanceof Closeable) {
                IOUtils.closeWhileHandlingException((Closeable) this.currentDatabase);
                this.currentDatabase = null;
            }
        });
    }

    private <T> T withLock(final Lock lock,
                           final Supplier<T> handler) {
        lock.lock();
        try {
            return handler.get();
        } finally {
            lock.unlock();
        }
    }
    private void withLock(final Lock lock,
                          final Runnable runnable) {
        this.<Void>withLock(lock, () -> {
            runnable.run();
            return null;
        });
    }
}
