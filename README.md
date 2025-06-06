# Quarkus Neo4j OGM Extension

This Quarkus extension provides seamless integration of Neo4j's Object-Graph Mapping (OGM) capabilities into Quarkus applications. It facilitates efficient and type-safe interactions with Neo4j graph databases by leveraging build-time code generation for repositories, mappers, and relationship loaders.

## Features

* **Build-Time Code Generation**: Generates type-safe repositories, entity mappers, and relationship loaders during compilation, eliminating runtime reflection overhead.
* **Dual Programming Models**: Supports both blocking (`Repository<T>`) and reactive (`ReactiveRepository<T>`) APIs for flexible data access patterns.
* **Automatic Relationship Management**: Handles loading of `@Relationship` annotated fields with cycle detection to prevent infinite loops.
* **Type Safety**: Ensures compile-time validation of database operations, reducing runtime errors.
* **Dependency Injection Support**: Provides `RepositoryRegistry` and `ReactiveRepositoryRegistry` for easy injection of repositories into your application.
* **GraalVM Native Image Compatibility**: Optimized for fast startup times and reduced memory footprint in native executables.

## Getting Started

### Prerequisites

* Java 17 or higher
* Maven
* Quarkus
* Neo4j Database

### Installation

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.quarkiverse.quarkus.neo4j</groupId>
    <artifactId>quarkus-neo4j-ogm</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Configuration

Configure the Neo4j connection in your `application.properties`:

```properties
quarkus.neo4j.uri=bolt://localhost:7687
quarkus.neo4j.authentication.username=neo4j
quarkus.neo4j.authentication.password=your_password
```

### Defining Entities

Annotate your domain classes with `@NodeEntity` and define relationships using `@Relationship`:

```java
@NodeEntity
public class Person {
    @Id
    private Long id;
    private String name;

    @Relationship(type = "FRIEND_OF")
    private List<Person> friends;
}
```

### Generating Repositories

Annotate your domain classes with `@GenerateRepository(GenerateRepository.RepositoryType.BOTH)`:

```java
@NodeEntity
@GenerateRepository(GenerateRepository.RepositoryType.BOTH)
public class Person {
    @Id
    private Long id;
    private String name;

    @Relationship(type = "FRIEND_OF")
    private List<Person> friends;
}
```

### Using Repositories

Inject the generated repositories into your services:

```java
@ApplicationScoped
public class PersonService {

    @Inject
    PersonBaseRepository personRepository;

    public List<Person> getAllPersons() {       
        return personRepository.findAll();
    }
}
```

For reactive operations:

```java
@ApplicationScoped
public class ReactivePersonService {

    @Inject
    PersonBaseReactiveRepository personRepository;

    public Uni<List<Person>> getAllPersons() {        
        return personRepository.findAll();
    }
}
```

## Relationship Management

The extension automatically handles the loading of related entities defined with `@Relationship`. It includes cycle detection mechanisms to prevent infinite loops during relationship traversal.

## Code Generation

During the build process, the extension generates:

* **Entity Mappers**: For converting between Java objects and Neo4j nodes/relationships.
* **Repositories**: Type-safe data access layers for each entity.
* **Relationship Loaders**: For managing the loading of related entities.

This approach ensures high performance and compatibility with native images.

## Testing

The extension supports integration testing with Neo4j using Testcontainers. Configure your tests to spin up a Neo4j container and verify repository operations.

## Contributing

Contributions are welcome! Please fork the repository and submit a pull request with your changes. Ensure that your code adheres to the existing coding standards and includes appropriate tests.

## License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.
