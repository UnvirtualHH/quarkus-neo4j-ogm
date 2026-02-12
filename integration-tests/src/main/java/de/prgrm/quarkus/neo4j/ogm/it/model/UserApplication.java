package de.prgrm.quarkus.neo4j.ogm.it.model;

import java.util.UUID;

import de.prgrm.quarkus.neo4j.ogm.runtime.enums.Convert;
import de.prgrm.quarkus.neo4j.ogm.runtime.enums.Direction;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.GenerateRepository;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.GeneratedValue;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.NodeEntity;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.NodeId;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.Relationship;

@NodeEntity(label = "UserApplication")
@GenerateRepository(GenerateRepository.RepositoryType.BOTH)
public class UserApplication {

    @NodeId
    @GeneratedValue(strategy = GeneratedValue.Strategy.UUID)
    private UUID id;

    @Relationship(type = "HAS_ACCESS_TO", direction = Direction.OUTGOING)
    private Application application;

    @Convert(ApplicationRoleConverter.class)
    private ApplicationRole role;

    public UserApplication() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Application getApplication() {
        return application;
    }

    public void setApplication(Application application) {
        this.application = application;
    }

    public ApplicationRole getRole() {
        return role;
    }

    public void setRole(ApplicationRole role) {
        this.role = role;
    }
}
