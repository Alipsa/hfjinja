package se.alipsa.hfjinja.internal.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class FuzzParserRunnerTest {
  @Test
  void preservesUnpairedSurrogateUtf16CodeUnits() throws Exception {
    Method read =
        Class.forName("se.alipsa.hfjinja.internal.parser.FuzzParserRunner$Candidate")
            .getDeclaredMethod("read", String.class);
    read.setAccessible(true);
    Object candidate =
        read.invoke(
            null,
            "{\"id\":\"x\",\"family\":\"hostile\",\"source\":\"QQAA2EIA\",\"trimBlocks\":true,\"lstripBlocks\":true,\"sourceCodeUnits\":3}");
    Method source = candidate.getClass().getDeclaredMethod("source");
    source.setAccessible(true);
    String value = (String) source.invoke(candidate);
    assertEquals(3, value.length());
    assertEquals('A', value.charAt(0));
    assertEquals('\uD800', value.charAt(1));
    assertEquals('B', value.charAt(2));
  }
}
