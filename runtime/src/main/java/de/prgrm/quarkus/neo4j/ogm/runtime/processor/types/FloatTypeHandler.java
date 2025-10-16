package de.prgrm.quarkus.neo4j.ogm.runtime.processor.types;

public class FloatTypeHandler extends AbstractSimpleTypeHandler {
    @Override
    protected String getSupportedType() {
        return "java.lang.Float";
    }

    @Override
    protected String readMethod() {
        return "asDouble";
    }
}
