package de.prgrm.quarkus.neo4j.ogm.it.model;

import java.util.UUID;

import de.prgrm.quarkus.neo4j.ogm.runtime.enums.Direction;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.*;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.GenerateRepository.RepositoryType;

/**
 * Issue #60: two relationships sharing the same type ("OWNS") but pointing to different node types.
 */
@NodeEntity
@GenerateRepository(RepositoryType.BLOCKING)
public class Owner {

    @NodeId
    @GeneratedValue(strategy = GeneratedValue.Strategy.UUID)
    private UUID id;

    private String name;

    @Relationship(type = "OWNS", direction = Direction.OUTGOING)
    private Car car;

    @Relationship(type = "OWNS", direction = Direction.OUTGOING)
    private House house;

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

    public Car getCar() {
        return car;
    }

    public void setCar(Car car) {
        this.car = car;
    }

    public House getHouse() {
        return house;
    }

    public void setHouse(House house) {
        this.house = house;
    }
}
