package lib.kasuga.test.registration.data_driven.source;

import lib.kasuga.registration.data_driven.source.LogicalPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogicalPathTest {
    @Test
    void acceptsRelativeSlashSeparatedPathsAndExposesSegments() {
        LogicalPath path = LogicalPath.of("data/example_railway/kasuga_lib/data_driven/main.json");

        assertEquals("data/example_railway/kasuga_lib/data_driven/main.json", path.value());
        assertEquals(List.of("data", "example_railway", "kasuga_lib", "data_driven", "main.json"),
                path.segments());
    }

    @Test
    void rejectsPathsThatCanEscapeOrAmbiguouslyResolveAgainstARoot() {
        for (String invalid : List.of(
                "", " ", "/absolute", "C:/windows", "C:relative", "\\\\server\\share",
                "data\\example", "data//main.json", "./data/main.json", "data/../main.json",
                "data/main.json/", "data/./main.json")) {
            assertThrows(IllegalArgumentException.class, () -> LogicalPath.of(invalid), invalid);
        }
    }

    @Test
    void rejectsControlCharacters() {
        assertThrows(IllegalArgumentException.class, () -> LogicalPath.of("data/bad\u0000name.json"));
        assertThrows(IllegalArgumentException.class, () -> LogicalPath.of("data/bad\nname.json"));
    }
}
