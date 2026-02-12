# Quarkus Neo4j OGM

[![Maven Central](https://img.shields.io/maven-central/v/de.prgrm.quarkus-neo4j-ogm/quarkus-neo4j-ogm.svg)](https://central.sonatype.com/artifact/de.prgrm.quarkus-neo4j-ogm/quarkus-neo4j-ogm)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java 25](https://img.shields.io/badge/Java-25-blue.svg)](https://openjdk.java.net/)

A modern, type-safe Object-Graph Mapping (OGM) extension for Quarkus that brings elegant Neo4j database integration with zero runtime reflection overhead.

## Why Quarkus Neo4j OGM?

This extension bridges the gap between Neo4j's powerful graph database capabilities and Quarkus's cloud-native philosophy:

- ⚡️ **Zero Runtime Overhead**: All repository code, mappers, and relationship loaders are generated at build time using annotation processing
- 🚀 **Native Image Ready**: Built for GraalVM native compilation with fast startup times and minimal memory footprint
- 🔄 **Dual APIs**: Choose between blocking (`Repository<T>`) or reactive (`ReactiveRepository<T>`) programming models
- 🎯 **Type Safety**: Compile-time validation of database operations eliminates an entire class of runtime errors
- 🔗 **Smart Relationship Handling**: Automatic relationship loading with cycle detection prevents infinite loops
- 🎨 **Context-Aware Converters**: Advanced converters that can access entity relationships during conversion
- 🧪 **Test-Friendly**: Seamless integration with Testcontainers for reliable integration tests

## Features

### Core Capabilities

- **Build-Time Code Generation**: Generates type-safe repositories, entity mappers, and relationship loaders during compilation
- **Imperative & Reactive**: Full support for both blocking and non-blocking programming models
- **Automatic Relationship Management**: Handles `@Relationship` fields with intelligent cycle detection
- **Custom Queries**: Define static Cypher queries with `@Query` annotations
- **Attribute Conversion**: Built-in converters for common types (Instant, Enum, etc.) plus support for custom converters
- **Context-Aware Conversion**: Converters can access entity relationships for sophisticated mapping logic
- **Transaction Management**: Integrates with Quarkus transaction handling
- **Batch Operations**: Efficient bulk save, update, and delete operations
- **Pagination Support**: Built-in pagination with `Pageable` and `Page<T>` abstractions

### Advanced Features

- **Relationship Entities**: First-class support for relationships with properties via `@RelationshipEntity`
- **Lazy/Eager Loading**: Control relationship loading behavior per entity
- **Projection Support**: Map query results to custom DTOs
- **Generated Value Strategies**: Support for UUID and custom ID generation
- **Enum Mapping**: Flexible enum persistence (ORDINAL or STRING)
- **Transient Fields**: Exclude fields from persistence with `@Transient`

## Getting Started

### Prerequisites

- **Java**: 21 or higher (Java 25 recommended)
- **Maven**: 3.8.6 or higher
- **Quarkus**: 3.30.6 or higher
- **Neo4j**: 5.x

### Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>de.prgrm.quarkus-neo4j-ogm</groupId>
    <artifactId>quarkus-neo4j-ogm</artifactId>
    <version>1.0.7</version>
</dependency>
```

### Configuration

Configure Neo4j connection in `application.properties`:

```properties
# Neo4j connection
quarkus.neo4j.uri=bolt://localhost:7687
quarkus.neo4j.authentication.username=neo4j
quarkus.neo4j.authentication.password=secret

# Optional: Connection pool settings
quarkus.neo4j.pool.max-connection-pool-size=50
quarkus.neo4j.pool.connection-acquisition-timeout=60s
```

## Usage Examples

### Defining Entities

Create a simple node entity:

```java
@NodeEntity(label = "Person")
@GenerateRepository(RepositoryType.BOTH)
public class Person {

    @NodeId
    @GeneratedValue(strategy = Strategy.UUID)
    private UUID id;

    private String name;
    private Integer age;

    @Property(name = "email_address")
    private String email;

    @Relationship(type = "LIVES_IN", direction = Direction.OUTGOING)
    private Address address;

    @Relationship(type = "FRIEND_OF", direction = Direction.UNDIRECTED)
    private List<Person> friends;

    // Constructors, getters, setters...
}
```

### Using Repositories

The `@GenerateRepository` annotation generates type-safe repository classes at build time:

#### Blocking API

```java
@ApplicationScoped
public class PersonService {

    @Inject
    PersonBaseRepository repository;

    public Person createPerson(String name, Integer age) {
        Person person = new Person();
        person.setName(name);
        person.setAge(age);
        return repository.save(person);
    }

    public Optional<Person> findById(UUID id) {
        return repository.findById(id);
    }

    public List<Person> findAll() {
        return repository.findAll();
    }

    public Page<Person> findPaginated(int page, int size) {
        return repository.findAll(PageRequest.of(page, size));
    }

    public void deletePerson(UUID id) {
        repository.deleteById(id);
    }
}
```

#### Reactive API

```java
@ApplicationScoped
public class ReactivePersonService {

    @Inject
    PersonBaseReactiveRepository repository;

    public Uni<Person> createPerson(String name, Integer age) {
        Person person = new Person();
        person.setName(name);
        person.setAge(age);
        return repository.save(person);
    }

    public Uni<Person> findById(UUID id) {
        return repository.findById(id);
    }

    public Uni<List<Person>> findAll() {
        return repository.findAll();
    }

    public Multi<Person> streamAll() {
        return repository.streamAll();
    }
}
```

### Custom Queries

Define custom Cypher queries with the `@Query` annotation:

```java
@NodeEntity(label = "Person")
@GenerateRepository(RepositoryType.BOTH)
@Queries({
    @Query(
        name = "findByName",
        cypher = "MATCH (p:Person {name: $name}) RETURN p AS node"
    ),
    @Query(
        name = "findByAgeRange",
        cypher = """
            MATCH (p:Person)
            WHERE p.age >= $minAge AND p.age <= $maxAge
            RETURN p AS node
            ORDER BY p.age
            """
    ),
    @Query(
        name = "findFriendsOfFriends",
        cypher = """
            MATCH (p:Person {id: $id})-[:FRIEND_OF*2]-(friend)
            WHERE friend.id <> $id
            RETURN DISTINCT friend AS node
            """
    )
})
public class Person {
    // Entity definition...
}
```

Use the generated query methods:

```java
// Blocking
List<Person> persons = repository.findByName("John");
List<Person> adults = repository.findByAgeRange(18, 65);
List<Person> fof = repository.findFriendsOfFriends(personId);

// Reactive
Uni<List<Person>> persons = repository.findByName("John");
Multi<Person> adults = repository.streamByAgeRange(18, 65);
```

### Relationship Entities

Model relationships with properties:

```java
@RelationshipEntity(type = "WORKS_IN")
public class Employment {

    @NodeId
    @GeneratedValue(strategy = Strategy.UUID)
    private UUID id;

    @StartNode
    private Person employee;

    @EndNode
    private Company company;

    private LocalDate startDate;
    private String position;
    private BigDecimal salary;

    // Getters, setters...
}

@NodeEntity(label = "Company")
public class Company {
    @NodeId
    @GeneratedValue(strategy = Strategy.UUID)
    private UUID id;

    private String name;

    @Relationship(type = "WORKS_IN", direction = Direction.INCOMING)
    private List<Employment> employees;
}
```

### Context-Aware Converters

Create converters that can access related entity data during conversion:

```java
@ApplicationScoped
public class RoleConverter implements ContextAwareAttributeConverter<String, String, UserApplication> {

    @Override
    public String toGraphProperty(String value, UserApplication entity) {
        // Store raw value to database
        return value;
    }

    @Override
    public String toEntityAttribute(String value, UserApplication entity) {
        // Access related application to enrich the role
        if (entity != null && entity.getApplication() != null) {
            String discriminator = entity.getApplication().getDiscriminator();
            return discriminator != null ? value + "_" + discriminator : value;
        }
        return value;
    }
}
```

Apply the converter:

```java
@NodeEntity(label = "UserApplication")
public class UserApplication {

    @NodeId
    @GeneratedValue(strategy = Strategy.UUID)
    private UUID id;

    @Convert(converter = RoleConverter.class)
    private String role;

    @Relationship(type = "HAS_ACCESS_TO", direction = Direction.OUTGOING)
    private Application application;
}
```

### Attribute Converters

Create custom converters for any type:

```java
@ApplicationScoped
public class MoneyConverter implements AttributeConverter<Money, Long> {

    @Override
    public Long toGraphProperty(Money value) {
        return value != null ? value.getCents() : null;
    }

    @Override
    public Money toEntityAttribute(Long value) {
        return value != null ? new Money(value) : null;
    }
}
```

### Transactional Operations

Leverage Quarkus transaction management:

```java
@ApplicationScoped
public class TransferService {

    @Inject
    PersonBaseRepository personRepository;

    @Transactional
    public void transferFriendship(UUID fromId, UUID toId, UUID friendId) {
        // All operations in same transaction
        Person from = personRepository.findById(fromId).orElseThrow();
        Person to = personRepository.findById(toId).orElseThrow();
        Person friend = personRepository.findById(friendId).orElseThrow();

        from.getFriends().remove(friend);
        to.getFriends().add(friend);

        personRepository.update(from);
        personRepository.update(to);

        // Automatic commit on success, rollback on exception
    }
}
```

### Batch Operations

Efficiently process multiple entities:

```java
List<Person> persons = createManyPersons();

// Batch save
List<Person> saved = repository.saveAll(persons);

// Batch delete
repository.deleteAll(persons);

// Delete by IDs
repository.deleteAllById(personIds);
```

## Repository API Reference

### Common Operations

Both `Repository<T>` and `ReactiveRepository<T>` provide:

| Method | Description | Returns |
|--------|-------------|---------|
| `save(T entity)` | Create or update entity | `T` / `Uni<T>` |
| `saveAll(Iterable<T>)` | Batch save | `List<T>` / `Uni<List<T>>` |
| `findById(Object id)` | Find by ID | `Optional<T>` / `Uni<T>` |
| `findAll()` | Find all entities | `List<T>` / `Uni<List<T>>` |
| `findAll(Pageable)` | Find page | `Page<T>` / `Uni<Page<T>>` |
| `count()` | Count entities | `long` / `Uni<Long>` |
| `existsById(Object id)` | Check existence | `boolean` / `Uni<Boolean>` |
| `deleteById(Object id)` | Delete by ID | `void` / `Uni<Void>` |
| `delete(T entity)` | Delete entity | `void` / `Uni<Void>` |
| `deleteAll()` | Delete all | `void` / `Uni<Void>` |
| `update(T entity)` | Update entity | `T` / `Uni<T>` |

### Reactive-Only Operations

| Method | Description |
|--------|-------------|
| `streamAll()` | Stream all entities as `Multi<T>` |
| Custom query `stream*` methods | Stream query results |

## Annotations Reference

### Entity Annotations

- `@NodeEntity(label)`: Marks a class as a Neo4j node
- `@RelationshipEntity(type)`: Marks a class as a Neo4j relationship
- `@GenerateRepository(type)`: Generates repository (BLOCKING, REACTIVE, or BOTH)

### Field Annotations

- `@NodeId`: Marks the ID field
- `@GeneratedValue(strategy)`: Auto-generate IDs (UUID or custom)
- `@Property(name)`: Custom property name in database
- `@Relationship(type, direction)`: Defines relationship
- `@Convert(converter)`: Apply custom converter
- `@Enumerated(value)`: Enum persistence strategy
- `@Transient`: Exclude field from persistence

### Relationship Entity Annotations

- `@StartNode`: Source node of relationship
- `@EndNode`: Target node of relationship

### Query Annotations

- `@Queries({...})`: Container for multiple queries
- `@Query(name, cypher)`: Define custom Cypher query

## Testing

### Integration Tests with Testcontainers

```java
@QuarkusTest
@TestProfile(Neo4jTestProfile.class)
public class PersonRepositoryTest {

    @Inject
    PersonBaseRepository repository;

    @Test
    public void testSaveAndFind() {
        Person person = new Person();
        person.setName("Alice");
        person.setAge(30);

        Person saved = repository.save(person);
        assertNotNull(saved.getId());

        Optional<Person> found = repository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("Alice", found.get().getName());
    }
}
```

Test profile with Testcontainers:

```java
public class Neo4jTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "quarkus.neo4j.uri", "bolt://localhost:7687",
            "quarkus.neo4j.authentication.username", "neo4j",
            "quarkus.neo4j.authentication.password", "test"
        );
    }
}
```

### Running Tests

```bash
# Run all tests
mvn clean test

# Run specific test
mvn test -Dtest=PersonRepositoryTest

# Skip tests
mvn clean install -DskipTests
```

## Building from Source

```bash
# Build with tests
mvn clean install

# Build without tests
mvn clean install -DskipTests

# Build native image
mvn clean package -Pnative
```

**Note**: The project uses Java 25. The `impsort-maven-plugin` is disabled as it doesn't yet support Java 25.

## Architecture

### Build-Time Code Generation

The extension uses annotation processing to generate code at compile time:

```
Your Entity Class
       ↓
  @NodeEntity
       ↓
Annotation Processor
       ↓
    Generates:
    ├── EntityMapper
    ├── BaseRepository
    ├── BaseReactiveRepository
    ├── RelationLoader
    └── ReactiveRelationLoader
```

### Two-Phase Relationship Loading

1. **Map Phase**: Entities are mapped from Neo4j records
2. **Load Relations Phase**: Relationships are loaded with cycle detection

### Context-Aware Conversion

1. **Store Phase**: `toGraphProperty()` called before storing
2. **Load Phase**: `toEntityAttribute()` called after relationships are loaded, allowing access to related entities

## Performance Tips

1. **Use Pagination**: For large result sets, always use pagination
2. **Batch Operations**: Use `saveAll()` for multiple entities
3. **Lazy Loading**: Set `mode = FETCH_ONLY` on relationships you don't always need
4. **Custom Queries**: Write optimized Cypher for complex scenarios
5. **Native Images**: Deploy as GraalVM native image for best startup time

## Troubleshooting

### Common Issues

**Problem**: Generated classes not found
```bash
# Solution: Ensure annotation processing is enabled
mvn clean compile
```

**Problem**: Relationship cycle causing stack overflow
```bash
# Solution: The extension includes cycle detection, but ensure
# your entity relationships are properly bidirectional
```

**Problem**: Native image build fails
```bash
# Solution: Check that all entities are registered for reflection
# This should be automatic, but verify no dynamic class loading occurs
```

## Roadmap

- [ ] Spring Data-style query methods (`findByNameAndAge`)
- [ ] Schema validation and migration tools
- [ ] Performance metrics and monitoring
- [ ] GraphQL integration
- [ ] Multi-database support

## Contributing

Contributions are welcome! Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Development Requirements

- Java 25
- Maven 3.8.6+
- Docker (for integration tests)
- Neo4j 5.x (via Testcontainers)

### Code Standards

- Follow existing code style
- Add tests for new features
- Update documentation
- Ensure all tests pass: `mvn clean test`

## License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.

## Acknowledgments

- Built on [Quarkus](https://quarkus.io/)
- Powered by [Neo4j Java Driver](https://github.com/neo4j/neo4j-java-driver)
- Inspired by [Spring Data Neo4j](https://spring.io/projects/spring-data-neo4j)

## Links

- [GitHub Repository](https://github.com/UnvirtualHH/quarkus-neo4j-ogm)
- [Issue Tracker](https://github.com/UnvirtualHH/quarkus-neo4j-ogm/issues)
- [Maven Central](https://central.sonatype.com/artifact/de.prgrm.quarkus-neo4j-ogm/quarkus-neo4j-ogm)
- [Neo4j Documentation](https://neo4j.com/docs/)
- [Quarkus Documentation](https://quarkus.io/guides/)

---

**Made with ❤️ for the Quarkus and Neo4j communities**
