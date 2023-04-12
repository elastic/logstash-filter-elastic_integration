# encoding: utf-8

########################################################################
# Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V.
# under one or more contributor license agreements. Licensed under the
# Elastic License 2.0; you may not use this file except in compliance
# with the Elastic License 2.0.
########################################################################

##
# This module contains methods for bridging the gap between the
# Ruby-API [LogStash::Event] and its Java-API [co.elastic.logstash.api.Event]
# counterpart, producing bidirectional zero-copy _views_ of one API for use
# with the other API.
module LogStash::Filters::ElasticIntegration::EventApiBridge

  ##
  # Converts a collection of Ruby-API events into a collection
  # of Java-API events.
  #
  # @param ruby_events [Array[LogStash::Event]]
  # @return [Array[co.elastic.logstash.api.Event]]
  def ruby_events_as_java(ruby_events)
    ruby_events.map do |ruby_event|
      mutable_java_view_of_ruby_event(ruby_event)
    end
  end

  ##
  # Converts a collection of Java-API events into a collection
  # of Ruby-API events.
  #
  # @param java_events [Array[co.elastic.logstash.api.Event]]
  # @return [Array[LogStash::Event]]
  def java_events_as_ruby(java_events)
    java_events.map do |java_event|
      mutable_ruby_view_of_java_event(java_event)
    end
  end

  ##
  # Returns the Java-API event that backs the provided Ruby-API event.
  # Mutations to the Java-API event are reflected by the Ruby-API event.
  #
  # @param ruby_api_event [LogStash::Event]
  # @return [co.elastic.logstash.api.Event]
  def mutable_java_view_of_ruby_event(ruby_api_event)
    ruby_api_event.to_java
  end

  # Because LS Core's RubyEvent.newRubyEvent(Runtime, Event)
  # requires the ruby runtime which is constant-once-defined,
  # we look it up and memoize it once.
  RUBY_RUNTIME = self.to_java.getMetaClass().to_java.getClassRuntime()
  private_constant :RUBY_RUNTIME

  ##
  # Returns a Ruby-API event that is backed by the provided Java-API event.
  # Mutations to the Ruby-API event directly modify the underlying Java-API event.
  #
  # @param java_api_event [co.elastic.logstash.api.Event]
  # @return [LogStash::Event]
  def mutable_ruby_view_of_java_event(java_api_event)
    org.logstash.ext.JrubyEventExtLibrary::RubyEvent.newRubyEvent(RUBY_RUNTIME, java_api_event)
  end
end