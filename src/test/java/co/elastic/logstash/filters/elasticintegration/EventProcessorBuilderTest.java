package co.elastic.logstash.filters.elasticintegration;

import org.elasticsearch.painless.spi.Whitelist;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;


class EventProcessorBuilderTest {

    @Test
    void getPainlessBaseWhiteList() {
        final List<Whitelist> painlessBaseWhiteList = EventProcessorBuilder.getPainlessBaseWhiteList();
        assertThat(painlessBaseWhiteList, is(not(empty())));
    }
}