package io.quarkiverse.quarkus.neo4j.ogm.it.model;

import java.util.UUID;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.*;

@NodeEntity(label = "Person")
@GenerateRepository(GenerateRepository.RepositoryType.BOTH)
public class Person {

    @NodeId
    @GeneratedValue(strategy = GeneratedValue.Strategy.UUID)
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
