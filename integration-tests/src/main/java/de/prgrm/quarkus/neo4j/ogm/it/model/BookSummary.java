package de.prgrm.quarkus.neo4j.ogm.it.model;

// @TypeUseMarker on a record component reproduces issue #61 for query projections.
public record BookSummary(@TypeUseMarker String title, boolean active) {
}
