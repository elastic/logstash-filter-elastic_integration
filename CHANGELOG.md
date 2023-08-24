## 0.0.3 (UNRELEASED)
  - Re-syncs with Elasticsearch 8.9 [#78](https://github.com/elastic/logstash-filter-elastic_integration/pull/78)
    - BREAKING: The `redact` processor was removed from upstream IngestCommon, and therefore no longer available here.
  - Documentation added for required privileges and unsupported processors [#72](https://github.com/elastic/logstash-filter-elastic_integration/pull/72)

## 0.0.2
  - Fixes several related issues with how fields are mapped from the Logstash Event to the IngestDocument and back again [#51](https://github.com/elastic/logstash-filter-elastic_integration/pull/51)
    - `IngestDocument` metadata fields are now separately routed to `[@metadata][_ingest_document]` on the resulting `Event`, fixing an issue where the presence of Elasticsearch-reserved fields such as the top-level `_version` would cause a downstream Elasticsearch output to be unable to index the event [#47][]
    - Top-level `@timestamp` and `@version` fields are no longer excluded from the `IngestDocument`, as required by some existing integration pipelines [#54][]
    - Field-type conversions have been improved by presenting logstash `Timestamp`-type objects as their ISO8601-encoded `String`s mapping any returned `ZonedDateTime`-objects into logstash `Timestamp`s to support several Ingest Common processors and their typical use in Elastic Integration pipelines [#65][], [#70][]
  - Adds proactive reloaders for both datastream-to-pipeline-name mappings and pipeline definitions to ensure upstream changes are made available without impacting processing [#48](https://github.com/elastic/logstash-filter-elastic_integration/pull/48)
  - Presents helpful guidance when run on an unsupported version of Java [#43](https://github.com/elastic/logstash-filter-elastic_integration/pull/43)
  - Fix: now plugin is able to establish a connection to Elasticsearch on Elastic cloud with `cloud_id` and `cloud_auth` authentication pair [#62](https://github.com/elastic/logstash-filter-elastic_integration/pull/62)
  - Adds `pipeline_name` to _override_ the default behaviour of auto-detecting the pipeline name from its data stream [#69](https://github.com/elastic/logstash-filter-elastic_integration/pull/69)
  - BREAKING: http basic authentication with Elasticsearch is now configured with `username` and `password` options to make this plugin behave more similarly to other Elasticsearch-related plugins [#61](https://github.com/elastic/logstash-filter-elastic_integration/pull/61)
  - Improves user-experience when connected to an Elasticsearch that does not have security features enabled (such as when testing against an on-prem cluster) [#64](https://github.com/elastic/logstash-filter-elastic_integration/pull/64)
    - Provides helpful guidance when providing request credentials to an unsecured Elasticsearch cluster. 
    - Tolerates anonymous access of an unsecured Elasticsearch cluster by allowing the plugin to start in an "unsafe" mode without pre-validating permission to use the necessary Elasticsearch APIs.

[#47]: https://github.com/elastic/logstash-filter-elastic_integration/issues/47
[#54]: https://github.com/elastic/logstash-filter-elastic_integration/issues/54
[#65]: https://github.com/elastic/logstash-filter-elastic_integration/issues/65
[#70]: https://github.com/elastic/logstash-filter-elastic_integration/issues/70

## 0.0.1
  - Empty Bootstrap of Logstash filter plugin [#1](https://github.com/logstash-plugins/logstash-filter-elastic_integration/pull/1)
  - Adds basic configuration options required for Elasticsearch connection [#2](https://github.com/logstash-plugins/logstash-filter-elastic_integration/pull/2)
