package eu.arrowhead.kalix.descriptor;

import eu.arrowhead.kalix.internal.util.PerfectCache;

import java.util.Objects;

/**
 * Describes a type of message payload encoding.
 */
public class EncodingDescriptor {
    private final String name;

    private EncodingDescriptor(final String name) {
        this.name = name;
    }

    /**
     * @return Encoding identifier.
     */
    public String name() {
        return name;
    }

    /**
     * Abstract Syntax Notation One (ASN1).
     *
     * @see <a href="https://tools.ietf.org/html/rfc6025">RFC 6025</a>
     */
    public static final EncodingDescriptor ASN1 = new EncodingDescriptor("ASN1");

    /**
     * Concise Binary Object Representation (CBOR).
     *
     * @see <a href="https://tools.ietf.org/html/rfc7049">RFC 7049</a>
     */
    public static final EncodingDescriptor CBOR = new EncodingDescriptor("CBOR");

    /**
     * JavaScript Object Notation (JSON).
     *
     * @see <a href="https://tools.ietf.org/html/rfc8259">RFC 8259</a>
     */
    public static final EncodingDescriptor JSON = new EncodingDescriptor("JSON");

    /**
     * Extensible Markup Language (XML).
     *
     * @see <a href="https://www.w3.org/TR/xml">W3C XML 1.0</a>
     * @see <a href="https://www.w3.org/TR/xml11">W3C XML 1.1</a>
     */
    public static final EncodingDescriptor XML = new EncodingDescriptor("XML");

    /**
     * Efficient XML Interchange (EXI).
     *
     * @see <a href="https://www.w3.org/TR/exi/">W3C EXI 1.0</a>
     */
    public static final EncodingDescriptor XSI = new EncodingDescriptor("XSI");

    private static final PerfectCache cache = new PerfectCache(0, 0,
        new PerfectCache.Entry(ASN1.name, ASN1),
        new PerfectCache.Entry(CBOR.name, CBOR),
        new PerfectCache.Entry(JSON.name, JSON),
        new PerfectCache.Entry(XML.name, XML),
        new PerfectCache.Entry(XSI.name, XSI)
    );

    /**
     * Resolves {@link EncodingDescriptor} from given {@code name}.
     *
     * @param name Name to resolve. Case insensitive.
     * @return Cached or new {@link EncodingDescriptor}.
     */
    public static EncodingDescriptor valueOf(String name) {
        name = Objects.requireNonNull(name, "Name required.").toUpperCase();
        final var value = cache.get(name);
        return value != null
            ? (EncodingDescriptor) value
            : new EncodingDescriptor(name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        final var encoding = (EncodingDescriptor) o;
        return name.equals(encoding.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return name;
    }
}