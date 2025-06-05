package io.quarkiverse.quarkus.neo4j.ogm.runtime.repository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Values;
import org.neo4j.driver.reactive.ReactiveResult;
import org.neo4j.driver.reactive.ReactiveSession;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.EntityMapper;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.EntityWithRelations;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.RelationLoader;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.RelationshipData;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

public abstract class ReactiveRepository<T> {

    protected final Driver driver;
    protected final String label;
    protected final EntityMapper<T> entityMapper;
    protected final ReactiveRepositoryRegistry reactiveRegistry;
    protected RelationLoader<T> relationLoader;

    public ReactiveRepository() {
        this.driver = null;
        this.label = null;
        this.entityMapper = null;
        this.reactiveRegistry = null;
        this.relationLoader = null;
    }

    public ReactiveRepository(Driver driver, String label, EntityMapper<T> entityMapper,
            ReactiveRepositoryRegistry reactiveRegistry) {
        this.driver = driver;
        this.label = label;
        this.entityMapper = entityMapper;
        this.reactiveRegistry = reactiveRegistry;
        this.relationLoader = null;
    }

    private static Function<ReactiveSession, Uni<Void>> closeSession() {
        return session -> Uni.createFrom().publisher(session.close()).replaceWithVoid();
    }

    protected abstract Class<T> getEntityType();

    public Uni<T> findById(Object id) {
        return runReadQuerySingle("MATCH (n:" + label + " {id: $id}) RETURN n AS node", Map.of("id", id))
                .flatMap(entity -> loadRelations(entity).replaceWith(entity));
    }

    public Multi<T> findAll() {
        return runReadQuery("MATCH (n:" + label + ") RETURN n AS node", Map.of())
                .onItem().transformToUniAndMerge(entity -> loadRelations(entity).replaceWith(entity));
    }

    public Uni<Long> count() {
        return runScalarReadQuery("MATCH (n:" + label + ") RETURN count(n) AS count", Map.of(), r -> r.get("count").asLong());
    }

    public Uni<T> create(T entity) {
        EntityWithRelations data = entityMapper.toDb(entity);
        Object id = entityMapper.getNodeId(entity);

        return runWriteQuerySingle("CREATE (n:" + label + " $props) RETURN n AS node", Map.of("props", data.getProperties()))
                .flatMap(saved -> persistRelationships(label, id, data.getRelationships()).replaceWith(saved));
    }

    public Uni<T> update(T entity) {
        Object id = entityMapper.getNodeId(entity);
        EntityWithRelations data = entityMapper.toDb(entity);

        return runWriteQuerySingle("MATCH (n:" + label + " {id: $id}) SET n += $props RETURN n AS node",
                Map.of("id", id, "props", data.getProperties()))
                .flatMap(updated -> persistRelationships(label, id, data.getRelationships()).replaceWith(updated));
    }

    public Uni<T> merge(T entity) {
        Object id = entityMapper.getNodeId(entity);
        EntityWithRelations data = entityMapper.toDb(entity);

        return runWriteQuerySingle("MERGE (n:" + label + " {id: $id}) SET n += $props RETURN n AS node",
                Map.of("id", id, "props", data.getProperties()))
                .flatMap(merged -> persistRelationships(label, id, data.getRelationships()).replaceWith(merged));
    }

    public Uni<Void> delete(T entity) {
        return deleteById(entityMapper.getNodeId(entity));
    }

    public Uni<Void> deleteById(Object id) {
        return runWriteQueryVoid("MATCH (n:" + label + " {id: $id}) DELETE n", Map.of("id", id));
    }

    public Uni<Boolean> existsById(Object id) {
        return runScalarReadQuery("MATCH (n:" + label + " {id: $id}) RETURN count(n) > 0 AS exists",
                Map.of("id", id), r -> r.get("exists").asBoolean());
    }

    public Uni<Boolean> exists(T entity) {
        return existsById(entityMapper.getNodeId(entity));
    }

    public Multi<T> query(String cypher) {
        return runReadQuery(cypher, Map.of());
    }

    public Multi<T> query(String cypher, Map<String, Object> params) {
        return runReadQuery(cypher, params)
                .onItem().transformToUniAndMerge(entity -> loadRelations(entity).replaceWith(entity));
    }

    public Uni<T> querySingle(String cypher) {
        return querySingle(cypher, Map.of());
    }

    public Uni<T> querySingle(String cypher, Map<String, Object> params) {
        return runWriteQuerySingle(cypher, params)
                .flatMap(entity -> loadRelations(entity).replaceWith(entity));
    }

    public <R> Uni<R> queryScalar(String cypher, Map<String, Object> params, Function<Record, R> mapper) {
        return runScalarReadQuery(cypher, params, mapper);
    }

    public Uni<Void> execute(String cypher, Map<String, Object> params) {
        return runWriteQueryVoid(cypher, params);
    }

    private Multi<T> runReadQuery(String cypher, Map<String, Object> params) {
        return runQueryInternal(cypher, params, true).map(r -> entityMapper.map(r, "node"));
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
                        return session.executeRead(tx -> Multi.createFrom().publisher(tx.run(cypher, Values.value(params)))
                                .flatMap(ReactiveResult::records));
                    } else {
                        return session.executeWrite(tx -> Multi.createFrom().publisher(tx.run(cypher, Values.value(params)))
                                .flatMap(ReactiveResult::records));
                    }
                })
                .withFinalizer(closeSession());
    }

    private Uni<Void> persistRelationships(String label, Object fromId, List<RelationshipData> relationships) {
        if (relationships == null || relationships.isEmpty()) {
            return Uni.createFrom().voidItem();
        }

        return Multi.createFrom().iterable(relationships)
                .onItem().transformToUniAndMerge(rel -> {
                    Object toId = rel.getTargetId();
                    if (toId == null && rel.getTargetEntity() != null) {
                        Object target = rel.getTargetEntity();
                        Class<?> targetType = target.getClass();
                        ReactiveRepository<Object> targetRepo = (ReactiveRepository<Object>) reactiveRegistry
                                .getReactiveRepository(targetType);
                        Object id = targetRepo.getEntityMapper().getNodeId(target);
                        Uni<Object> idUni = id == null
                                ? targetRepo.create(target).map(saved -> targetRepo.getEntityMapper().getNodeId(saved))
                                : Uni.createFrom().item(id);

                        return idUni.invoke(rel::setTargetId).replaceWithVoid();
                    }
                    return Uni.createFrom().voidItem();
                })
                .collect().asList()
                .flatMap(list -> {
                    return Multi.createFrom().iterable(relationships)
                            .onItem().transformToUniAndMerge(rel -> {
                                Object toId = rel.getTargetId();
                                if (toId == null)
                                    return Uni.createFrom().voidItem();
                                String query = switch (rel.getDirection()) {
                                    case OUTGOING ->
                                        "MATCH (a:" + label + " {id: $from}), (b {id: $to}) MERGE (a)-[r:" + rel.getType()
                                                + "]->(b)";
                                    case INCOMING ->
                                        "MATCH (a:" + label + " {id: $from}), (b {id: $to}) MERGE (a)<-[r:" + rel.getType()
                                                + "]-(b)";
                                    default ->
                                        throw new UnsupportedOperationException("Unsupported direction: " + rel.getDirection());
                                };
                                return runWriteQueryVoid(query, Map.of("from", fromId, "to", toId));
                            })
                            .collect().asList()
                            .replaceWithVoid();
                });
    }

    /**
     * Loads all relationships for the given entity.
     *
     * @param entity The entity to load relationships for
     * @return A Uni that completes when all relationships are loaded
     */
    protected Uni<Void> loadRelations(T entity) {
        if (entity == null || relationLoader == null) {
            return Uni.createFrom().voidItem();
        }

        Object id = entityMapper.getNodeId(entity);
        if (id == null) {
            return Uni.createFrom().voidItem();
        }

        return Uni.createFrom().voidItem()
                .invoke(() -> relationLoader.loadRelations(entity))
                .emitOn(java.util.concurrent.Executors.newSingleThreadExecutor());
    }

    public EntityMapper<T> getEntityMapper() {
        return entityMapper;
    }
}
