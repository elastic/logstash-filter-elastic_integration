## 0.1.14
  - Fix: register available PainlessExtension-s, resolving an issue where the pipelines for some integrations would fail to compile [#162](https://github.com/elastic/logstash-filter-elastic_integration/pull/162)

## 0.1.13
  - Update default elasticsearch tree branch to 8.15 [#156](https://github.com/elastic/logstash-filter-elastic_integration/pull/156)

## 0.1.12
  - Updates Elasticsearch Java client used[#155](https://github.com/elastic/logstash-filter-elastic_integration/pull/155)

## 0.1.11
  - [DOC] Documents that integrations are designed to work best with data streams and ECS enabled [#153](https://github.com/elastic/logstash-filter-elastic_integration/pull/153)

## 0.1.10
  - Fixes handling of array-type event fields by treating them as lists [#146](https://github.com/elastic/logstash-filter-elastic_integration/pull/146)
  - Syncs with Elasticsearch 8.14, including support for new user-provided GeoIP database types `ConnectionType`, `Domain` and `Isp` [#147](https://github.com/elastic/logstash-filter-elastic_integration/pull/147)

## 0.1.9
  - [DOC] Removes Tech Preview label and adds link to extending integrations topic in LSR [#142](https://github.com/elastic/logstash-filter-elastic_integration/pull/142)

## 0.1.8
  - Fixes `EventProcessorBuilder#build` to work with JRuby 9.4.6.0 [#133](https://github.com/elastic/logstash-filter-elastic_integration/pull/133)

## 0.1.7
  - Fixes `GeoIpDatabaseProvider.Builder#build` to work with JRuby 9.4.6.0 [#132](https://github.com/elastic/logstash-filter-elastic_integration/pull/132)

## 0.1.6
  - Fixes issue where configured `username`/`password` credentials was not sent to Elasticsearch instances that had anonymous access enabled [#127](https://github.com/elastic/logstash-filter-elastic_integration/pull/127)

## 0.1.5
  - Adds relevant information to Elasticsearch client's User-Agent header [#117](https://github.com/elastic/logstash-filter-elastic_integration/pull/117)

## 0.1.4
  - Non-user facing work to shorten JAR path when packaging [#114](https://github.com/elastic/logstash-filter-elastic_integration/pull/114)

## 0.1.3
  - [DOC] Additional links and formatting fixes to docs [#115](https://github.com/elastic/logstash-filter-elastic_integration/pull/115)

## 0.1.2
  - Synchronize with Elasticsearch 8.12 and include elasticsearch-geo jar to include a missed class [#113](https://github.com/elastic/logstash-filter-elastic_integration/pull/113)

## 0.1.1
  - Support non-encoded API Key [#101](https://github.com/elastic/logstash-filter-elastic_integration/pull/101)

## 0.1.0
  - Re-syncs with Elasticsearch 8.11 [#91](https://github.com/elastic/logstash-filter-elastic_integration/pull/91)
  - Adds support for `reroute` processor [#100](https://github.com/elastic/logstash-filter-elastic_integration/pull/100)
  - Adds support for `geoip` processor to use databases from Logstash's Geoip Database Management service [#88](https://github.com/elastic/logstash-filter-elastic_integration/pull/88)
  - Restores support for `redact` processor using its x-pack licensed implementation [#90](https://github.com/elastic/logstash-filter-elastic_integration/issues/90)

## 0.0.3
  - Re-syncs with Elasticsearch 8.10 [#78](https://github.com/elastic/logstash-filter-elastic_integration/pull/78)
    - BREAKING: The `redact` processor was removed from upstream IngestCommon, and therefore no longer available here.
  - Documentation added for required privileges and unsupported processors [#72](https://github.com/elastic/logstash-filter-elastic_integration/pull/72)
  - Added request header `Elastic-Api-Version` for serverless [#84](https://github.com/elastic/logstash-filter-elastic_integration/pull/84)

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
