package se.alipsa.hfjinja;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import se.alipsa.hfjinja.internal.ast.Statement;

class TemplateConcurrencyTest {
  private static final int WORKERS = 16;
  private static final int ROUNDS = 8;
  private static final String SOURCE =
      "{% macro wrap() %}[{{ caller() }}]{% endmacro %}"
          + "{% set ns = namespace(seen=false) %}"
          + "{% set state.seen = id %}"
          + "{% set ns.seen = id %}"
          + "{% if use_host %}{{ format_tool(id) }}{% endif %}"
          + "{% if fail %}{{ raise_exception(id) }}{% endif %}"
          + "{% filter upper %}{% call wrap() %}{{ id }}:"
          + "{% for value in values %}{{ loop.index }}={{ value }};{% endfor %}"
          + ":{{ state.seen }}:{{ ns.seen }}{% endcall %}{% endfilter %}";

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void oneParsedTemplateRendersIndependentlyAcrossThreads() throws Exception {
    var template = Template.parse(SOURCE);
    var hostCalls = new AtomicInteger();
    var options =
        RenderOptions.builder()
            .hostFunction(
                "format_tool",
                arguments -> {
                  hostCalls.incrementAndGet();
                  return "host-" + arguments.getFirst();
                })
            .build();
    var ready = new CountDownLatch(WORKERS);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(WORKERS, daemonThreadFactory());
    var futures = new ArrayList<Future<?>>();
    try {
      for (var worker = 0; worker < WORKERS; worker++) {
        var workerId = worker;
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                      throw new AssertionError(
                          "worker " + workerId + " did not receive start signal");
                    }
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(
                        "worker " + workerId + " was interrupted", interrupted);
                  }
                  for (var round = 0; round < ROUNDS; round++) {
                    try {
                      renderAndAssert(template, options, workerId, round);
                    } catch (AssertionError | RuntimeException failure) {
                      throw new AssertionError(
                          "worker " + workerId + ", round " + round + " failed", failure);
                    }
                  }
                }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS), "not every worker reached the start barrier");
      start.countDown();
      for (var future : futures) future.get(20, TimeUnit.SECONDS);
      assertEquals(WORKERS * ROUNDS / 2, hostCalls.get());
    } finally {
      start.countDown();
      shutdown(executor);
    }
  }

  @Test
  void templateStoresOnlyItsFinalParsedProgram() throws Exception {
    var template = Template.parse("{{ value }}");
    var fields =
        Arrays.stream(Template.class.getDeclaredFields())
            .filter(field -> !field.isSynthetic())
            .toList();
    assertEquals(1, fields.size());
    var program = fields.getFirst();
    assertEquals("program", program.getName());
    assertEquals(Statement.Program.class, program.getType());
    assertTrue(Modifier.isFinal(program.getModifiers()));
    program.setAccessible(true);
    var parsedProgram = program.get(template);
    for (var value = 0; value < 10; value++)
      assertEquals(Integer.toString(value), template.render(Map.of("value", value)));
    assertEquals(parsedProgram, program.get(template));
    assertNotNull(parsedProgram);
  }

  private static void renderAndAssert(
      Template template, RenderOptions options, int worker, int round) {
    var id = "W" + worker + "R" + round;
    var overload = (worker + round) % 4;
    var explicitOptions = overload == 1 || overload == 3;
    var fail = overload == 3 && round % 2 == 0;
    var state = new LinkedHashMap<String, Object>();
    state.put("seen", "caller-value");
    state.put("nested", new ArrayList<>(List.of("first", "second")));
    var expectedState = copyState(state);
    var context = new LinkedHashMap<String, Object>();
    context.put("id", id);
    context.put("state", state);
    context.put("values", List.of("a", "b"));
    context.put("use_host", explicitOptions);
    context.put("fail", fail);

    if (fail) {
      var error =
          explicitOptions
              ? capture(() -> template.render(context, options))
              : capture(() -> template.render(context));
      assertEquals(ErrorCategory.EXPLICIT_RAISE, error.category());
      assertTrue(error.getMessage().contains(id));
    } else {
      var expected =
          (explicitOptions ? "host-" + id : "") + "[" + id + ":1=A;2=B;:" + id + ":" + id + "]";
      String actual;
      switch (overload) {
        case 0 -> actual = template.render(context);
        case 1 -> actual = template.render(context, options);
        case 2 -> {
          var output = new StringBuilder();
          template.render(context, output);
          actual = output.toString();
        }
        case 3 -> {
          var output = new StringBuilder();
          template.render(context, output, options);
          actual = output.toString();
        }
        default -> throw new AssertionError("unreachable overload selection");
      }
      assertEquals(expected, actual);
    }
    assertEquals(expectedState, state);
  }

  private static TemplateRenderException capture(RenderCall call) {
    try {
      call.run();
    } catch (TemplateRenderException error) {
      return error;
    }
    throw new AssertionError("expected template render failure");
  }

  private static Map<String, Object> copyState(Map<String, Object> state) {
    var copy = new LinkedHashMap<String, Object>();
    copy.put("seen", state.get("seen"));
    copy.put("nested", new ArrayList<>((List<?>) state.get("nested")));
    return copy;
  }

  private static ThreadFactory daemonThreadFactory() {
    var sequence = new AtomicLong();
    return task -> {
      var thread = new Thread(task, "hfjinja-concurrency-" + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private static void shutdown(ExecutorService executor) throws InterruptedException {
    executor.shutdown();
    if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "worker executor did not stop");
    }
  }

  @FunctionalInterface
  private interface RenderCall {
    void run();
  }
}
