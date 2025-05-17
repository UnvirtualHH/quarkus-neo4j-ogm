package io.quarkiverse.quarkus.neo4j.ogm.it.model;

import java.util.UUID;

import io.quarkiverse.quarkus.neo4j.ogm.runtime.mapping.*;

@NodeEntity(label = "Address")
@GenerateRepository(GenerateRepository.RepositoryType.BOTH)
public class Address {

    @NodeId
    @GeneratedValue(strategy = GeneratedValue.Strategy.UUID)
    private UUID id;

    @Property(name = "street")
    private String street;

    @Property(name = "housenumber")
    private String housenumber;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getStreet() {
        return street;
    }

    public void setStreet(String street) {
        this.street = street;
    }

    public String getHousenumber() {
        return housenumber;
    }

    public void setHousenumber(String housenumber) {
        this.housenumber = housenumber;
    }
}
