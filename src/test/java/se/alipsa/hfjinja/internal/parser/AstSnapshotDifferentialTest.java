package se.alipsa.hfjinja.internal.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import se.alipsa.hfjinja.TemplateOptions;
import se.alipsa.hfjinja.internal.lexer.Lexer;
import org.junit.jupiter.api.Test;

/** Compares checked-in Node snapshots with the Java parser for each named fixture. */
class AstSnapshotDifferentialTest {
  private static final Pattern FIXTURE = Pattern.compile("\\{\\\"name\\\":\\\"([^\\\"]+)\\\",\\\"source\\\":\\\"((?:\\\\.|[^\\\"])*)\\\"");

  @Test
  void matchesPinnedNodeParserSnapshots() throws Exception {
    var fixtures = Files.readString(Path.of("src/test/resources/ast-snapshots/fixtures.json"));
    var expected = blocks(Files.readString(Path.of("src/test/resources/ast-snapshots/upstream-parsed.txt")));
    var matcher = FIXTURE.matcher(fixtures);
    var actual = new LinkedHashMap<String, String>();
    while (matcher.find()) {
      var name = matcher.group(1);
      var source = unescape(matcher.group(2));
      actual.put(name, AstSnapshot.of(Parser.parse(Lexer.tokenize(source, TemplateOptions.DEFAULT), TemplateOptions.DEFAULT)));
    }
    assertEquals(expected.keySet(), actual.keySet(), "fixture and snapshot names differ");
    for (var entry : actual.entrySet()) assertEquals(expected.get(entry.getKey()), entry.getValue(), entry.getKey());
  }

  private static Map<String, String> blocks(String input) {
    var result = new LinkedHashMap<String, String>(); String name = null; var body = new StringBuilder();
    for (var line : input.split("(?<=\\n)")) {
      if (line.startsWith("=== ")) { if (name != null) result.put(name, body.toString()); name = line.substring(4, line.indexOf(' ', 4)); body.setLength(0); }
      else body.append(line);
    }
    if (name != null) result.put(name, body.toString()); return result;
  }
  private static String unescape(String value) { return value.replace("\\\\\"", "\"").replace("\\\\n", "\n").replace("\\\\\\\\", "\\"); }
}
