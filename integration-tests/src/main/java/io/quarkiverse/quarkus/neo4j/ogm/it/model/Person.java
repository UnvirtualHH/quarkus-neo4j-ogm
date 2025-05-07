package io.quarkiverse.quarkus.neo4j.ogm.it.model;

import java.util.UUID;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.GenerateRepository;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.NodeEntity;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.NodeId;
import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.Property;

@NodeEntity(label = "Person")
@GenerateRepository(GenerateRepository.RepositoryType.BOTH)
public class Person {

    @NodeId
    private UUID id;

    @Property(name = "name")
    private String name;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
