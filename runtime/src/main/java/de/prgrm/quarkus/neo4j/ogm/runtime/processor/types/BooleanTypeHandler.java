package de.prgrm.quarkus.neo4j.ogm.runtime.processor.types;

public class BooleanTypeHandler extends AbstractSimpleTypeHandler {
    @Override
    protected String getSupportedType() {
        return "java.lang.Boolean";
    }

    @Override
    protected String readMethod() {
        return "asBoolean";
    }
}
