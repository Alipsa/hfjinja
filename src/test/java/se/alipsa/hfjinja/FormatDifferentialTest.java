package se.alipsa.hfjinja;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Compares every checked-in pinned-Node formatting golden with the public Java API. */
class FormatDifferentialTest {
  private static final Pattern RECORD =
      Pattern.compile(
          "\\{\\\"name\\\":\\\"((?:\\\\.|[^\\\"])*)\\\",\\\"source\\\":\\\"((?:\\\\.|[^\\\"])*)\\\",\\\"indent\\\":\\{(?:(?:\\\"default\\\":true)|(?:\\\"number\\\":(-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?))|(?:\\\"string\\\":\\\"((?:\\\\.|[^\\\"])*)\\\"))\\},\\\"roundTrip\\\":\\\"([^\\\"]+)\\\",\\\"formatted\\\":\\\"((?:\\\\.|[^\\\"])*)\\\"\\}");

  @Test
  void matchesEveryPinnedNodeFormatGolden() throws Exception {
    var input = Files.readString(Path.of("src/test/resources/format/upstream-formatted.json"));
    Matcher matcher = RECORD.matcher(input);
    int records = 0;
    while (matcher.find()) {
      String name = unescape(matcher.group(1));
      String source = unescape(matcher.group(2));
      String expected = unescape(matcher.group(6));
      var template = Template.parse(source);
      String actual =
          matcher.group(3) != null
              ? template.format(Double.parseDouble(matcher.group(3)))
              : matcher.group(4) != null
                  ? template.format(unescape(matcher.group(4)))
                  : template.format();
      assertEquals(expected, actual, name);
      records++;
    }
    assertEquals(countNames(input), records, "golden parser must consume every record");
  }

  private static int countNames(String value) {
    return value.split("\\\"name\\\":", -1).length - 1;
  }

  private static String unescape(String value) {
    var out = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c != '\\') out.append(c);
      else {
        if (++i == value.length()) throw new IllegalArgumentException("Incomplete JSON escape");
        char escaped = value.charAt(i);
        out.append(
            switch (escaped) {
              case '/' -> '/';
              case 'b' -> '\b';
              case 'f' -> '\f';
              case 'n' -> '\n';
              case 'r' -> '\r';
              case 't' -> '\t';
              case '\\' -> '\\';
              case '"' -> '"';
              case 'u' -> unicodeEscape(value, i);
              default -> throw new IllegalArgumentException("Unsupported JSON escape: " + escaped);
            });
        if (escaped == 'u') i += 4;
      }
    }
    return out.toString();
  }

  private static char unicodeEscape(String value, int escapeIndex) {
    if (escapeIndex + 4 >= value.length())
      throw new IllegalArgumentException("Incomplete JSON escape");
    return (char) Integer.parseInt(value.substring(escapeIndex + 1, escapeIndex + 5), 16);
  }
}
