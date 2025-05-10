package io.quarkiverse.quarkus.neo4j.ogm.runtime.repository;

import java.util.List;
import java.util.Map;

import org.neo4j.driver.*;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.EntityMapper;

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

    public T findById(Object id) {
        try (Session session = driver.session()) {
            String cypher = "MATCH (n:" + label + " {id: $id}) RETURN n AS node";
            Result result = session.run(cypher, Values.parameters("id", id));
            return entityMapper.map(result.single(), "node");
        }
    }

    public List<T> findAll() {
        try (Session session = driver.session()) {
            String cypher = "MATCH (n:" + label + ") RETURN n AS node";
            Result result = session.run(cypher);
            return result.list(record -> entityMapper.map(record, "node"));
        }
    }

    public long count() {
        try (Session session = driver.session()) {
            String cypher = "MATCH (n:" + label + ") RETURN count(n) AS count";
            return session.run(cypher).single().get("count").asLong();
        }
    }

    public T create(T entity) {
        try (Session session = driver.session()) {
            String cypher = "CREATE (n:" + label + " $props) RETURN n AS node";
            Result result = session.run(cypher, Values.parameters("props", entityMapper.toDb(entity)));
            return entityMapper.map(result.single(), "node");
        }
    }

    public T update(T entity) {
        Object id = entityMapper.getNodeId(entity);
        if (id == null)
            throw new IllegalArgumentException("Entity ID cannot be null");

        try (Session session = driver.session()) {
            String cypher = "MATCH (n:" + label + " {id: $id}) SET n += $props RETURN n AS node";
            Result result = session.run(cypher,
                    Values.parameters("id", id, "props", entityMapper.toDb(entity)));
            return entityMapper.map(result.single(), "node");
        }
    }

    public T merge(T entity) {
        Object id = entityMapper.getNodeId(entity);
        if (id == null)
            throw new IllegalArgumentException("Entity ID cannot be null");

        try (Session session = driver.session()) {
            String cypher = "MERGE (n:" + label + " {id: $id}) SET n += $props RETURN n AS node";
            Result result = session.run(cypher,
                    Values.parameters("id", id, "props", entityMapper.toDb(entity)));
            return entityMapper.map(result.single(), "node");
        }
    }

    public void delete(T entity) {
        Object id = entityMapper.getNodeId(entity);
        if (id == null)
            throw new IllegalArgumentException("Entity ID cannot be null");

        try (Session session = driver.session()) {
            String cypher = "MATCH (n:" + label + " {id: $id}) DELETE n";
            session.run(cypher, Values.parameters("id", id));
        }
    }

    public void deleteById(Object id) {
        if (id == null)
            throw new IllegalArgumentException("ID cannot be null");

        try (Session session = driver.session()) {
            String cypher = "MATCH (n:" + label + " {id: $id}) DELETE n";
            session.run(cypher, Values.parameters("id", id));
        }
    }

    public boolean exists(T entity) {
        Object id = entityMapper.getNodeId(entity);
        if (id == null)
            throw new IllegalArgumentException("Entity ID cannot be null");

        try (Session session = driver.session()) {
            String cypher = "MATCH (n:" + label + " {id: $id}) RETURN count(n) > 0 AS exists";
            return session.run(cypher, Values.parameters("id", id))
                    .single().get("exists").asBoolean();
        }
    }

    public boolean existsById(Object id) {
        try (Session session = driver.session()) {
            String cypher = "MATCH (n:" + label + " {id: $id}) RETURN count(n) > 0 AS exists";
            return session.run(cypher, Values.parameters("id", id))
                    .single().get("exists").asBoolean();
        }
    }

    public List<T> query(String cypher) {
        try (Session session = driver.session()) {
            Result result = session.run(cypher);
            return result.list(record -> entityMapper.map(record, "node"));
        }
    }

    public List<T> query(String cypher, Map<String, Object> parameters) {
        try (Session session = driver.session()) {
            Result result = session.run(cypher, Values.value(parameters));
            return result.list(record -> entityMapper.map(record, "node"));
        }
    }

    public T querySingle(String cypher) {
        try (Session session = driver.session()) {
            return entityMapper.map(session.run(cypher).single(), "node");
        }
    }

    public T querySingle(String cypher, Map<String, Object> parameters) {
        try (Session session = driver.session()) {
            return entityMapper.map(session.run(cypher, Values.value(parameters)).single(), "node");
        }
    }
}
