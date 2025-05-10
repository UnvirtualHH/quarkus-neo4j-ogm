package io.quarkiverse.quarkus.neo4j.ogm.runtime.repository;

import java.util.Map;
import java.util.function.Function;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Values;
import org.neo4j.driver.reactive.ReactiveResult;
import org.neo4j.driver.reactive.ReactiveSession;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.EntityMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

public abstract class ReactiveRepository<T> {

    protected final Driver driver;
    protected final String label;
    protected final EntityMapper<T> entityMapper;

    public ReactiveRepository() {
        this.driver = null;
        this.label = null;
        this.entityMapper = null;
    }

    public ReactiveRepository(Driver driver, String label, EntityMapper<T> entityMapper) {
        this.driver = driver;
        this.label = label;
        this.entityMapper = entityMapper;
    }

    private static Function<ReactiveSession, Uni<Void>> closeSession() {
        return session -> Uni.createFrom().publisher(session.close());
    }

    public Uni<T> findById(Object id) {
        if (id == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("ID cannot be null"));
        }
        String cypher = "MATCH (n:" + label + " {id: $id}) RETURN n AS node";
        Map<String, Object> params = Map.of("id", id);
        return runQuerySingleAndMap(cypher, params);
    }

    public Multi<T> findAll() {
        String cypher = "MATCH (n:" + label + ") RETURN n AS node";
        return runQueryAndMap(cypher, Map.of());
    }

    public Uni<Long> count() {
        String cypher = "MATCH (n:" + label + ") RETURN count(n) AS count";
        return Multi.createFrom().resource(
                () -> driver.session(ReactiveSession.class),
                session -> session.executeRead(tx -> {
                    var result = tx.run(cypher);
                    return Multi.createFrom().publisher(result)
                            .flatMap(ReactiveResult::records)
                            .map(r -> r.get("count").asLong());
                }))
                .withFinalizer(closeSession())
                .toUni()
                .onItem().ifNull().failWith(() -> new RuntimeException("Count query returned no result"));
    }

    Uni<Void> sessionFinalizer(ReactiveSession session) {
        return Uni.createFrom().publisher(session.close()).replaceWithVoid();
    }

    public Uni<T> create(T entity) {
        return Multi.createFrom().resource(
                () -> driver.session(ReactiveSession.class),
                session -> session.executeWrite(tx -> {
                    var result = tx.run(
                            "CREATE (n:" + label + " $props) RETURN n AS node",
                            Values.parameters("props", entityMapper.toDb(entity)));

                    return Multi.createFrom().publisher(result)
                            .flatMap(ReactiveResult::records);
                }))
                .withFinalizer(this::sessionFinalizer)
                .collect().asList()
                .map(records -> entityMapper.map((Record) records.get(0), "node"));
    }

    public Uni<T> update(T entity) {
        String cypher = "MATCH (n:" + label + " {id: $id}) SET n += $props RETURN n AS node";
        Map<String, Object> params = Map.of(
                "id", entityMapper.getNodeId(entity),
                "props", entityMapper.toDb(entity));
        return runQuerySingleAndMap(cypher, params);
    }

    public Uni<T> merge(T entity) {
        String cypher = "MERGE (n:" + label + " {id: $id}) SET n += $props RETURN n AS node";
        Map<String, Object> params = Map.of(
                "id", entityMapper.getNodeId(entity),
                "props", entityMapper.toDb(entity));
        return runQuerySingleAndMap(cypher, params);
    }

    public Uni<Void> delete(T entity) {
        String cypher = "MATCH (n:" + label + " {id: $id}) DELETE n";
        return Multi.createFrom().resource(
                        () -> driver.session(ReactiveSession.class),
                        session -> session.executeWrite(tx -> {
                            var result = tx.run(cypher, Values.parameters("id", entityMapper.getNodeId(entity)));
                            return Multi.createFrom().publisher(result)
                                    .flatMap(ReactiveResult::consume).flatMap(ignore -> Multi.createFrom().item((Void) null));
                        }))
                .withFinalizer(closeSession())
                .toUni();
    }

    public Uni<Void> deleteById(Object id) {
        if (id == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("ID cannot be null"));
        }
        String cypher = "MATCH (n:" + label + " {id: $id}) DELETE n";
        return Multi.createFrom().resource(
                () -> driver.session(ReactiveSession.class),
                session -> session.executeWrite(tx -> {
                    var result = tx.run(cypher, Values.parameters("id", id));
                    return Multi.createFrom().publisher(result)
                            .flatMap(ReactiveResult::consume).flatMap(ignore -> Multi.createFrom().item((Void) null));
                }))
                .withFinalizer(closeSession())
                .toUni();
    }

    public Multi<T> query(String cypher) {
        return runQueryAndMap(cypher, Map.of());
    }

    public Multi<T> query(String cypher, Map<String, Object> parameters) {
        return runQueryAndMap(cypher, parameters);
    }

    public Uni<T> querySingle(String cypher) {
        return runQuerySingleAndMap(cypher, Map.of());
    }

    public Uni<T> querySingle(String cypher, Map<String, Object> parameters) {
        return runQuerySingleAndMap(cypher, parameters);
    }

    private Multi<T> runQueryAndMap(String cypher, Map<String, Object> parameters) {
        return Multi.createFrom().resource(
                () -> driver.session(ReactiveSession.class),
                session -> session.executeRead(tx -> {
                    var result = tx.run(cypher, Values.value(parameters));
                    return Multi.createFrom().publisher(result)
                            .flatMap(ReactiveResult::records)
                            .map(r -> entityMapper.map(r, "node"));
                }))
                .withFinalizer(closeSession());
    }

    private Uni<T> runQuerySingleAndMap(String cypher, Map<String, Object> parameters) {
        return runQueryAndMap(cypher, parameters)
                .toUni()
                .onItem().ifNull().failWith(() -> new RuntimeException("No result found"));
    }
}
