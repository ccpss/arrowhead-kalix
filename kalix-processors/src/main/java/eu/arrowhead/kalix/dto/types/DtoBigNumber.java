package eu.arrowhead.kalix.dto.types;

import javax.lang.model.type.DeclaredType;

public class DtoBigNumber implements DtoType {
    private final DeclaredType type;
    private final DtoDescriptor descriptor;

    public DtoBigNumber(final DeclaredType type, final DtoDescriptor descriptor) {
        this.type = type;
        this.descriptor = descriptor;
    }

    @Override
    public DtoDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public DeclaredType asTypeMirror() {
        return type;
    }

    @Override
    public String toString() {
        return type.asElement().getSimpleName().toString();
    }
}