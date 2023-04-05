/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.util;

import co.elastic.logstash.api.Event;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static co.elastic.logstash.filters.elasticintegration.util.EventTestUtil.eventFromMap;
import static co.elastic.logstash.filters.elasticintegration.util.EventUtil.safeExtractValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class EventUtilTest {
    @Test
    void testEnsureValidFieldReferenceBareword() {
        EventUtil.ensureValidFieldReference("abc", "bareword");
    }
    @Test
    void testEnsureValidFieldReferencePathspec() {
        EventUtil.ensureValidFieldReference("[a][b][c]", "path spec");
    }
    @Test
    void testEnsureValidFieldReferenceNestedPathspec() {
        EventUtil.ensureValidFieldReference("[a][[b][c]]", "nestedpath spec");
    }
    @Test
    void testEnsureValidFieldReferenceBroken() {
        assertThrows(IllegalArgumentException.class, () -> {
            EventUtil.ensureValidFieldReference("ab[c", "corrupt");
        });
    }
    @Test
    void testEnsureValidFieldReferenceTrailingBrackets() {
        assertThrows(IllegalArgumentException.class, () -> {
            EventUtil.ensureValidFieldReference("a[]", "trailing brackets");
        });
    }
    @Test
    void testEnsureValidFieldReferenceTrailingIndexBrackets() {
        assertThrows(IllegalArgumentException.class, () -> {
            EventUtil.ensureValidFieldReference("abc[0]", "trailing index brackets");
        });
    }

    @Test
    void testSafeExtractValue() {
        final Event event = eventFromMap(Map.of(
                "nest", Map.of(
                        "list-str", List.of("a","b","c"),
                        "list-lon", List.of(1,2,3),
                        "lon", 17,
                        "str", "ok-string")));

        assertAll("success cases", () -> {
            assertThat(safeExtractValue(event, "[nest][str]", String.class), is(equalTo("ok-string")));
            assertThat(safeExtractValue(event, "[nest][list-str][1]", String.class), is(equalTo("b")));

            assertThat(safeExtractValue(event, "[nest][lon]", Long.class), is(equalTo(17L)));
            assertThat(safeExtractValue(event, "[nest][list-lon][1]", Long.class), is(equalTo(2L)));
        });

        assertAll("missing value", () -> {
            assertThat(safeExtractValue(event, "[missing][str]", String.class), is(nullValue()));
            assertThat(safeExtractValue(event, "[missing][list-str][1]", String.class), is(nullValue()));
        });

        assertAll("casting issue", () -> {
            assertThat(safeExtractValue(event, "[nest]", String.class), is(nullValue()));
            assertThat(safeExtractValue(event, "[missing][list-str][1]", Long.class), is(nullValue()));
        });
    }

}