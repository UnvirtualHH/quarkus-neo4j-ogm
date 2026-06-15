package de.prgrm.quarkus.neo4j.ogm.it.relationship;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;

import de.prgrm.quarkus.neo4j.ogm.it.model.*;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #60: an entity may declare multiple relationships with the same type pointing to different
 * node types. They must compile, persist, load and update independently.
 */
@QuarkusTest
class MultipleSameTypeRelationshipTest {

    @Inject
    OwnerBaseRepository ownerRepository;

    @Inject
    CarBaseRepository carRepository;

    @Inject
    HouseBaseRepository houseRepository;

    @Inject
    Driver driver;

    @AfterEach
    void cleanup() {
        try (Session session = driver.session()) {
            session.run("MATCH (n:Owner) DETACH DELETE n");
            session.run("MATCH (n:Car) DETACH DELETE n");
            session.run("MATCH (n:House) DETACH DELETE n");
        }
    }

    @Test
    void multipleSameTypeRelationshipsArePersistedAndLoaded() {
        Car car = new Car();
        car.setName("BMW");
        car = carRepository.create(car);

        House house = new House();
        house.setName("Villa");
        house = houseRepository.create(house);

        Owner owner = new Owner();
        owner.setName("Alice");
        owner.setCar(car);
        owner.setHouse(house);
        owner = ownerRepository.create(owner);

        Owner loaded = ownerRepository.findById(owner.getId());
        assertNotNull(loaded.getCar(), "Car relationship (type OWNS -> Car) must be loaded");
        assertEquals("BMW", loaded.getCar().getName());
        assertNotNull(loaded.getHouse(), "House relationship (type OWNS -> House) must be loaded");
        assertEquals("Villa", loaded.getHouse().getName());
    }

    @Test
    void partialUpdateDoesNotClobberOtherSameTypeRelationship() {
        Car car = new Car();
        car.setName("BMW");
        car = carRepository.create(car);

        House house = new House();
        house.setName("Villa");
        house = houseRepository.create(house);

        Owner owner = new Owner();
        owner.setName("Bob");
        owner.setCar(car);
        owner.setHouse(house);
        owner = ownerRepository.create(owner);

        // Update touching only the car (house left null => "leave unchanged"). Because the delete is
        // scoped to the target label, the OWNS -> House edge must NOT be removed (issue #60).
        Owner toUpdate = ownerRepository.findById(owner.getId());
        toUpdate.setHouse(null);
        ownerRepository.update(toUpdate);

        Owner reloaded = ownerRepository.findById(owner.getId());
        assertNotNull(reloaded.getCar(), "Car edge must remain");
        assertEquals("BMW", reloaded.getCar().getName());
        assertNotNull(reloaded.getHouse(),
                "House edge (same type, different target) must survive a partial update of the car");
        assertEquals("Villa", reloaded.getHouse().getName());
    }
}
