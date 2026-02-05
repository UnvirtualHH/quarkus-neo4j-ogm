package de.prgrm.quarkus.neo4j.ogm.it.model;

import java.util.UUID;

import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.GenerateRepository;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.GeneratedValue;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.NodeEntity;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.NodeId;

@NodeEntity(label = "Application")
@GenerateRepository(GenerateRepository.RepositoryType.BOTH)
public class Application {

    @NodeId
    @GeneratedValue(strategy = GeneratedValue.Strategy.UUID)
    private UUID id;

    private String name;

    private String discriminator;

    public Application() {
    }

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

    public String getDiscriminator() {
        return discriminator;
    }

    public void setDiscriminator(String discriminator) {
        this.discriminator = discriminator;
    }
}
