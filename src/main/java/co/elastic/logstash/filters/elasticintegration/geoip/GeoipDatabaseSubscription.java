package co.elastic.logstash.filters.elasticintegration.geoip;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;

public class GeoipDatabaseSubscription {
    private ValidatableGeoIpDatabase currentDatabase;

    private final Function<String,ValidatableGeoIpDatabase> databaseInitializer;

    private final ReentrantReadWriteLock.ReadLock readLock;
    private final ReentrantReadWriteLock.WriteLock writeLock;
    {
        final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
        readLock = readWriteLock.readLock();
        writeLock = readWriteLock.writeLock();
    }

    public GeoipDatabaseSubscription(final Consumer<SubscriptionObserver> subscription,
                                     final Function<String, ValidatableGeoIpDatabase> databaseInitializer) {
        this.databaseInitializer = databaseInitializer;
    }



    interface DbInfo {
        String getPath();
        boolean isExpired();
        boolean isPending();
        boolean isRemoved();
    }

    public class SubscriptionObserver {
        public void construct(final DbInfo initialDbInfo) {

        }
        public void onUpdate(final DbInfo updatedDbInfo) {

        }
        public void onExpire() {

        }
    }
}
