package co.elastic.logstash.filters.elasticintegration;

import co.elastic.logstash.api.Event;
import org.elasticsearch.core.Releasable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public class IntegrationBatch {
    final ArrayList<Event> events;

    public IntegrationBatch(Collection<Event> events) {
        this.events = new ArrayList<>(events);
    }

    void eachRequest(Supplier<Releasable> releasableSupplier, Consumer<IntegrationRequest> consumer) {
        for (int i = 0; i < this.events.size(); i++) {
            consumer.accept(new Request(i, releasableSupplier.get()));
        }
    }

    private class Request implements IntegrationRequest {
        private final int idx;
        private final Releasable handle;

        public Request(final int idx, final Releasable releasable) {
            this.idx = idx;
            this.handle = releasable;
        }

        @Override
        public Event event() {
            return IntegrationBatch.this.events.get(idx);
        }

        @Override
        public void complete(UnaryOperator<Event> eventSwapper) {
            final Event sourceEvent = event();
            final Event resultEvent = eventSwapper.apply(sourceEvent);

            if (resultEvent != sourceEvent) {
                events.set(idx, resultEvent);
            }

            handle.close();
        }
    }
}
