package de.prgrm.quarkus.neo4j.ogm.it.model;

import java.util.List;
import java.util.UUID;

import de.prgrm.quarkus.neo4j.ogm.runtime.enums.Direction;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.*;

@NodeEntity(label = "Person")
@GenerateRepository(GenerateRepository.RepositoryType.BOTH)
public class Person {

    @NodeId
    @GeneratedValue(strategy = GeneratedValue.Strategy.UUID)
    private UUID id;

    @Property(name = "name")
    private String name;

    @Relationship(type = "follows", direction = Direction.OUTGOING)
    private List<Person> following;

    @Relationship(type = "located_in", direction = Direction.OUTGOING)
    private Address address;

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

    public List<Person> getFollowing() {
        return following;
    }

    public void setFollowing(List<Person> following) {
        this.following = following;
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }
}
