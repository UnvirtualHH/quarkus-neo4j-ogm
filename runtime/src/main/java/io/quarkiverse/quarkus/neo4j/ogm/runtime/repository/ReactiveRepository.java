package io.quarkiverse.quarkus.neo4j.ogm.runtime.repository;

import java.util.Map;
import java.util.function.Function;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;
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
        return session -> Uni.createFrom().publisher(session.close()).replaceWithVoid();
    }

    public Uni<T> findById(Object id) {
        return runReadQuerySingle("MATCH (n:" + label + " {id: $id}) RETURN n AS node", Map.of("id", id));
    }

    public Multi<T> findAll() {
        return runReadQuery("MATCH (n:" + label + ") RETURN n AS node", Map.of());
    }

    public Uni<Long> count() {
        return runScalarReadQuery("MATCH (n:" + label + ") RETURN count(n) AS count", Map.of(), r -> r.get("count").asLong());
    }

    public Uni<T> create(T entity) {
        String cypher = "CREATE (n:" + label + " $props) RETURN n AS node";
        return runWriteQuerySingle(cypher, Map.of("props", entityMapper.toDb(entity)));
    }

    public Uni<T> update(T entity) {
        String cypher = "MATCH (n:" + label + " {id: $id}) SET n += $props RETURN n AS node";
        return runWriteQuerySingle(cypher, Map.of(
                "id", entityMapper.getNodeId(entity),
                "props", entityMapper.toDb(entity)));
    }

    public Uni<T> merge(T entity) {
        String cypher = "MERGE (n:" + label + " {id: $id}) SET n += $props RETURN n AS node";
        return runWriteQuerySingle(cypher, Map.of(
                "id", entityMapper.getNodeId(entity),
                "props", entityMapper.toDb(entity)));
    }

    public Uni<Void> delete(T entity) {
        return deleteById(entityMapper.getNodeId(entity));
    }

    public Uni<Void> deleteById(Object id) {
        String cypher = "MATCH (n:" + label + " {id: $id}) DELETE n";
        return runWriteQueryVoid(cypher, Map.of("id", id));
    }

    public Uni<Boolean> existsById(Object id) {
        String cypher = "MATCH (n:" + label + " {id: $id}) RETURN count(n) > 0 AS exists";
        return runScalarReadQuery(cypher, Map.of("id", id), r -> r.get("exists").asBoolean());
    }

    public Uni<Boolean> exists(T entity) {
        return existsById(entityMapper.getNodeId(entity));
    }

    public Multi<T> query(String cypher) {
        return runReadQuery(cypher, Map.of());
    }

    public Multi<T> query(String cypher, Map<String, Object> params) {
        return runReadQuery(cypher, params);
    }

    public Uni<T> querySingle(String cypher) {
        return runWriteQuerySingle(cypher, Map.of());
    }

    public Uni<T> querySingle(String cypher, Map<String, Object> params) {
        return runWriteQuerySingle(cypher, params);
    }

    public <R> Uni<R> queryScalar(String cypher, Map<String, Object> params, Function<Record, R> mapper) {
        return runScalarReadQuery(cypher, params, mapper);
    }

    public Uni<Void> execute(String cypher, Map<String, Object> params) {
        return runWriteQueryVoid(cypher, params);
    }

    private Multi<T> runReadQuery(String cypher, Map<String, Object> params) {
        return runQueryInternal(cypher, params, true)
                .map(r -> entityMapper.map(r, "node"));
    }

    private Uni<T> runReadQuerySingle(String cypher, Map<String, Object> params) {
        return runReadQuery(cypher, params)
                .toUni()
                .onItem().ifNull().failWith(() -> new RuntimeException("No result found"));
    }

    private Uni<T> runWriteQuerySingle(String cypher, Map<String, Object> params) {
        return runQueryInternal(cypher, params, false)
                .collect().asList()
                .map(records -> {
                    if (records.isEmpty()) {
                        throw new RuntimeException("No result found");
                    }
                    return entityMapper.map(records.get(0), "node");
                });
    }

    private Uni<Void> runWriteQueryVoid(String cypher, Map<String, Object> params) {
        return Multi.createFrom().resource(
                        () -> driver.session(ReactiveSession.class),
                        session -> session.executeWrite(tx -> {
                            var result = tx.run(cypher, Values.value(params));
                            return Multi.createFrom().publisher(result)
                                    .flatMap(ReactiveResult::consume)
                                    .flatMap(ignore -> Multi.createFrom().item((Void) null));
                        }))
                .withFinalizer(closeSession())
                .toUni();
    }

    private <R> Uni<R> runScalarReadQuery(String cypher, Map<String, Object> params, Function<Record, R> mapper) {
        return Multi.createFrom().resource(
                        () -> driver.session(ReactiveSession.class),
                        session -> session.executeRead(tx -> {
                            var result = tx.run(cypher, Values.value(params));
                            return Multi.createFrom().publisher(result)
                                    .flatMap(ReactiveResult::records)
                                    .map(mapper);
                        }))
                .withFinalizer(closeSession())
                .toUni()
                .onItem().ifNull().failWith(() -> new RuntimeException("No scalar result"));
    }

    private Multi<Record> runQueryInternal(String cypher, Map<String, Object> params, boolean readOnly) {
        return Multi.createFrom().resource(
                        () -> driver.session(ReactiveSession.class),
                        session -> {
                            if (readOnly) {
                                return session.executeRead(tx -> {
                                    var result = tx.run(cypher, Values.value(params));
                                    return Multi.createFrom().publisher(result).flatMap(ReactiveResult::records);
                                });
                            } else {
                                return session.executeWrite(tx -> {
                                    var result = tx.run(cypher, Values.value(params));
                                    return Multi.createFrom().publisher(result).flatMap(ReactiveResult::records);
                                });
                            }
                        })
                .withFinalizer(closeSession());
    }
}
