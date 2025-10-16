package de.prgrm.quarkus.neo4j.ogm.runtime.processor.relations;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

import de.prgrm.quarkus.neo4j.ogm.runtime.enums.Direction;
import de.prgrm.quarkus.neo4j.ogm.runtime.enums.RelationshipMode;
import de.prgrm.quarkus.neo4j.ogm.runtime.mapping.Relationship;

abstract class AbstractRelationLoaderGenerator {

    protected boolean shouldFetchRelationship(Relationship rel) {
        return rel.mode() == RelationshipMode.FETCH_ONLY
                || rel.mode() == RelationshipMode.FETCH_AND_PERSIST;
    }

    protected String buildQuery(String sourceLabel, Direction direction, String relationType, String targetLabel) {
        String left = direction == Direction.INCOMING ? "<-" : "-";
        String right = direction == Direction.OUTGOING ? "->" : "-";
        return String.format("MATCH (n:%s {id: $id})%s[:%s]%s(m:%s) RETURN m as node",
                sourceLabel, left, relationType, right, targetLabel);
    }

    protected String resolveSetterName(VariableElement field) {
        String name = field.getSimpleName().toString();
        return "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    protected String resolveGetterName(VariableElement field) {
        String name = field.getSimpleName().toString();
        return "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    abstract void generateRelationLoader(
            String packageName,
            TypeElement entityType,
            String loaderClassName,
            javax.annotation.processing.ProcessingEnvironment processingEnv);
}
