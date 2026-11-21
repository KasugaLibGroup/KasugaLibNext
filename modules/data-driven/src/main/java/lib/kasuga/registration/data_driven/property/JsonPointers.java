package lib.kasuga.registration.data_driven.property;

/** Minimal RFC 6901 escaping used by strict property diagnostics. */
final class JsonPointers {
    private JsonPointers() {
    }

    static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }
}
