package io.quarkiverse.quarkus.neo4j.ogm.runtime.repository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.neo4j.driver.*;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.EntityMapper;
import org.neo4j.driver.Record;

public abstract class Repository<T> {

    protected final Driver driver;
    protected final String label;
    protected final EntityMapper<T> entityMapper;

    public Repository() {
        this.driver = null;
        this.label = null;
        this.entityMapper = null;
    }

    public Repository(Driver driver, String label, EntityMapper<T> entityMapper) {
        this.driver = driver;
        this.label = label;
        this.entityMapper = entityMapper;
    }

    private <R> R inWriteTx(Function<Transaction, R> work) {
        try (Session session = driver.session()) {
            try (Transaction tx = session.beginTransaction()) {
                R result = work.apply(tx);
                tx.commit();
                return result;
            }
        }
    }

    private void inWriteTxVoid(java.util.function.Consumer<Transaction> work) {
        try (Session session = driver.session()) {
            try (Transaction tx = session.beginTransaction()) {
                work.accept(tx);
                tx.commit();
            }
        }
    }

    public T findById(Object id) {
        return inWriteTx(tx -> {
            var result = tx.run("MATCH (n:" + label + " {id: $id}) RETURN n AS node",
                    Values.parameters("id", id));
            return entityMapper.map(result.single(), "node");
        });
    }

    public List<T> findAll() {
        return inWriteTx(tx -> {
            var result = tx.run("MATCH (n:" + label + ") RETURN n AS node");
            return result.list(r -> entityMapper.map(r, "node"));
        });
    }

    public long count() {
        return inWriteTx(tx -> {
            var result = tx.run("MATCH (n:" + label + ") RETURN count(n) AS count");
            return result.single().get("count").asLong();
        });
    }

    public T create(T entity) {
        return inWriteTx(tx -> {
            var result = tx.run("CREATE (n:" + label + " $props) RETURN n AS node",
                    Values.parameters("props", entityMapper.toDb(entity)));
            return entityMapper.map(result.single(), "node");
        });
    }

    public T update(T entity) {
        Object id = entityMapper.getNodeId(entity);
        if (id == null)
            throw new IllegalArgumentException("Entity ID cannot be null");

        return inWriteTx(tx -> {
            var result = tx.run("MATCH (n:" + label + " {id: $id}) SET n += $props RETURN n AS node",
                    Values.parameters("id", id, "props", entityMapper.toDb(entity)));
            return entityMapper.map(result.single(), "node");
        });
    }

    public T merge(T entity) {
        Object id = entityMapper.getNodeId(entity);
        if (id == null)
            throw new IllegalArgumentException("Entity ID cannot be null");

        return inWriteTx(tx -> {
            var result = tx.run("MERGE (n:" + label + " {id: $id}) SET n += $props RETURN n AS node",
                    Values.parameters("id", id, "props", entityMapper.toDb(entity)));
            return entityMapper.map(result.single(), "node");
        });
    }

    public void delete(T entity) {
        deleteById(entityMapper.getNodeId(entity));
    }

    public void deleteById(Object id) {
        if (id == null)
            throw new IllegalArgumentException("ID cannot be null");

        inWriteTxVoid(tx -> {
            tx.run("MATCH (n:" + label + " {id: $id}) DELETE n", Values.parameters("id", id)).consume();
        });
    }

    public boolean existsById(Object id) {
        return inWriteTx(tx -> {
            var result = tx.run("MATCH (n:" + label + " {id: $id}) RETURN count(n) > 0 AS exists",
                    Values.parameters("id", id));
            return result.single().get("exists").asBoolean();
        });
    }

    public boolean exists(T entity) {
        return existsById(entityMapper.getNodeId(entity));
    }

    public List<T> query(String cypher) {
        return query(cypher, Map.of());
    }

    public List<T> query(String cypher, Map<String, Object> parameters) {
        return inWriteTx(tx -> tx.run(cypher, Values.value(parameters))
                .list(r -> entityMapper.map(r, "node")));
    }

    public T querySingle(String cypher) {
        return querySingle(cypher, Map.of());
    }

    public T querySingle(String cypher, Map<String, Object> parameters) {
        return inWriteTx(tx -> entityMapper.map(
                tx.run(cypher, Values.value(parameters)).single(), "node"));
    }

    public void execute(String cypher, Map<String, Object> parameters) {
        inWriteTxVoid(tx -> tx.run(cypher, Values.value(parameters)).consume());
    }

    public <R> R queryScalar(String cypher, Function<Record, R> mapper) {
        return queryScalar(cypher, Map.of(), mapper);
    }

    public <R> R queryScalar(String cypher, Map<String, Object> parameters, Function<Record, R> mapper) {
        return inWriteTx(tx -> mapper.apply(tx.run(cypher, Values.value(parameters)).single()));
    }
}
