package mozaki.redmineUpster.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ConsoleEncodingTests {

    @Test
    void argWinsOverEnv() {
        Optional<Charset> cs = ConsoleEncoding.resolve(new String[] { "--sync", "--console-encoding=MS932" },
                Map.of(ConsoleEncoding.ENV, "UTF-8", "JENKINS_URL", "http://jenkins/"));
        assertEquals(Charset.forName("windows-31j"), cs.orElseThrow());
    }

    @Test
    void envWinsOverJenkins() {
        Optional<Charset> cs = ConsoleEncoding.resolve(new String[] { "--sync" },
                Map.of(ConsoleEncoding.ENV, "Windows-31J", "JENKINS_URL", "http://jenkins/"));
        assertEquals(Charset.forName("windows-31j"), cs.orElseThrow());
    }

    @Test
    void jenkinsMeansUtf8() {
        Optional<Charset> cs = ConsoleEncoding.resolve(new String[0], Map.of("JENKINS_URL", "http://jenkins/"));
        assertEquals(StandardCharsets.UTF_8, cs.orElseThrow());
    }

    @Test
    void nothingMeansEmpty() {
        assertTrue(ConsoleEncoding.resolve(new String[] { "--sync" }, Map.of()).isEmpty());
    }

    @Test
    void invalidNameMeansEmpty() {
        assertTrue(ConsoleEncoding.resolve(new String[] { "--console-encoding=no-such-cs" },
                Map.of("JENKINS_URL", "http://jenkins/")).isEmpty());
        assertTrue(ConsoleEncoding.resolve(new String[0], Map.of(ConsoleEncoding.ENV, "bogus!")).isEmpty());
    }
}
