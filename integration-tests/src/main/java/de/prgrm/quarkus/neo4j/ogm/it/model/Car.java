package de.prgrm.quarkus.neo4j.ogm.it.model;

import java.util.UUID;

import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.*;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.GenerateRepository.RepositoryType;

@NodeEntity
@GenerateRepository(RepositoryType.BLOCKING)
public class Car {

    @NodeId
    @GeneratedValue(strategy = GeneratedValue.Strategy.UUID)
    private UUID id;

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
