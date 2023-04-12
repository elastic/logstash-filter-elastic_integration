/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
 * under one or more contributor license agreements. Licensed under the
 * Elastic License 2.0; you may not use this file except in compliance
 * with the Elastic License 2.0.
 */
package co.elastic.logstash.filters.elasticintegration.geoip;

import org.elasticsearch.ingest.geoip.GeoIpDatabase;

public interface ValidatableGeoIpDatabase extends GeoIpDatabase {
    boolean isValid();
}
