package se.alipsa.hfjinja.release;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates an isolated detached worktree and runs the release candidate matrix. */
public final class ReleaseVerifierMain {
  private static final Pattern COORDINATES =
      Pattern.compile("\\\"coordinates\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

  private ReleaseVerifierMain() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3) {
      throw new IllegalArgumentException(
          "usage: ReleaseVerifierMain <source-dir> <gradle-user-home> [--allow-dirty]");
    }
    Path source = Path.of(args[0]).toAbsolutePath().normalize();
    Path userHome = Path.of(args[1]).toAbsolutePath().normalize();
    boolean allowDirty = args.length == 3 && "--allow-dirty".equals(args[2]);
    String status = output(source, "git", "status", "--porcelain");
    if (!status.isBlank() && !allowDirty)
      throw new IllegalStateException("source checkout is dirty:\n" + status);
    String head = output(source, "git", "rev-parse", "HEAD").trim();
    String coordinates = contractCoordinates(source.resolve("req/release-verification.json"));
    Path parent = Files.createTempDirectory("hfjinja-release-worktree-");
    Path worktree = parent.resolve("candidate");
    Path dependencyEvidence = Files.createTempDirectory("hfjinja-dependency-evidence-");
    Path repository = Files.createTempDirectory("hfjinja-release-repository-");
    Path report = source.resolve("build/reports/release-verification.md");
    try {
      run(source, "git", "worktree", "add", "--detach", worktree.toString(), head);
      Files.createDirectories(userHome);
      Files.writeString(
          worktree.resolve("gradle.properties"),
          "\norg.gradle.java.installations.auto-download=false\n",
          java.nio.file.StandardOpenOption.APPEND);

      // Each destructive operation gets a separate Gradle process and graph.
      gradle(worktree, userHome, false, "verifyReproducibleArchives");
      gradle(worktree, userHome, false, "clean", "check");
      gradle(worktree, userHome, false, "dependencyUpdates");
      Path stagedReport = dependencyEvidence.resolve("dependency-updates.txt");
      Files.copy(worktree.resolve("build/dependencyUpdates/report.txt"), stagedReport);

      gradle(worktree, userHome, true, "verifyReproducibleArchives");
      gradle(worktree, userHome, true, "clean", "check");
      gradle(
          worktree,
          userHome,
          true,
          "dependencyReview",
          "-PreleaseVerificationDependencyReport=" + stagedReport);
      gradle(
          worktree,
          userHome,
          true,
          "publishMavenPublicationToReleaseVerificationRepository",
          "-PreleaseVerificationRepository=" + repository);
      Path resolved = runConsumer(worktree, userHome, repository, coordinates);
      Path published = publishedJar(repository, coordinates);
      if (!sha256(published).equals(sha256(resolved))) {
        throw new IllegalStateException(
            "consumer resolved artifact does not match the published candidate JAR");
      }
      writeReport(
          report, head, status, allowDirty, worktree, source, published, resolved, coordinates);
    } finally {
      runQuietly(source, "git", "worktree", "remove", "--force", worktree.toString());
      runQuietly(source, "git", "worktree", "prune");
      deleteTree(parent);
      deleteTree(dependencyEvidence);
      deleteTree(repository);
    }
  }

  private static void gradle(Path project, Path userHome, boolean offline, String... tasks)
      throws Exception {
    String wrapper =
        System.getProperty("os.name").toLowerCase().contains("win") ? "gradlew.bat" : "gradlew";
    List<String> command =
        new ArrayList<>(
            List.of(
                project.resolve(wrapper).toString(), "--gradle-user-home", userHome.toString()));
    if (offline) command.add("--offline");
    command.addAll(List.of(tasks));
    requireIsolation(command, offline);
    run(project, command.toArray(String[]::new));
  }

  private static void requireIsolation(List<String> command, boolean offline) {
    if (!command.contains("--gradle-user-home") || (offline && !command.contains("--offline"))) {
      throw new IllegalStateException(
          "refusing to launch a candidate Gradle command without required isolation flags");
    }
  }

  private static Path runConsumer(
      Path candidate, Path userHome, Path repository, String coordinates) throws Exception {
    Path consumer = Files.createTempDirectory("hfjinja-release-consumer-");
    String[] coordinate = coordinates.split(":", -1);
    if (coordinate.length != 3)
      throw new IllegalStateException("invalid contract coordinates: " + coordinates);
    try {
      Files.writeString(
          consumer.resolve("settings.gradle"),
          "pluginManagement { repositories { } }\nrootProject.name = 'hfjinja-release-consumer'\n");
      String repositoryUri = repository.toUri().toString().replace("'", "\\'");
      Files.writeString(
          consumer.resolve("build.gradle"),
          """
          plugins { id 'java' }
          repositories { maven { url = uri('%s') } }
          dependencies { implementation '%s' }
          java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
          configurations.configureEach {
            resolutionStrategy.cacheChangingModulesFor 0, 'seconds'
            resolutionStrategy.cacheDynamicVersionsFor 0, 'seconds'
          }
          tasks.register('consumerVerify', JavaExec) {
            dependsOn tasks.named('classes')
            classpath = sourceSets.main.runtimeClasspath
            mainClass = 'consumer.ConsumerMain'
          }
          tasks.register('copyResolvedCandidate', Copy) {
            from configurations.runtimeClasspath
            include '%s-*.jar'
            exclude '*-sources.jar', '*-javadoc.jar'
            into layout.buildDirectory.dir('resolved')
          }
          tasks.named('consumerVerify') { finalizedBy tasks.named('copyResolvedCandidate') }
          """
              .formatted(repositoryUri, coordinates, coordinate[1]));
      Files.writeString(
          consumer.resolve("gradle.properties"),
          "org.gradle.java.installations.auto-download=false\n");
      Path source = consumer.resolve("src/main/java/consumer/ConsumerMain.java");
      Files.createDirectories(source.getParent());
      Files.writeString(
          source,
          """
          package consumer;
          import java.util.Map;
          import se.alipsa.hfjinja.Template;
          public final class ConsumerMain {
            public static void main(String[] args) { System.out.print(Template.parse(\"{{ value }}\").render(Map.of(\"value\", \"verified\"))); }
          }
          """);
      String wrapper =
          System.getProperty("os.name").toLowerCase().contains("win") ? "gradlew.bat" : "gradlew";
      run(
          consumer,
          candidate.resolve(wrapper).toString(),
          "--gradle-user-home",
          userHome.toString(),
          "consumerVerify");
      try (var files = Files.list(consumer.resolve("build/resolved"))) {
        return files
            .filter(
                path ->
                    path.getFileName().toString().startsWith(coordinate[1] + "-")
                        && path.getFileName().toString().endsWith(".jar"))
            .findFirst()
            .orElseThrow(
                () -> new IllegalStateException("consumer did not resolve the candidate JAR"));
      }
    } finally {
      deleteTree(consumer);
    }
  }

  private static Path publishedJar(Path repository, String coordinates) throws Exception {
    String[] coordinate = coordinates.split(":", -1);
    Path directory =
        repository
            .resolve(coordinate[0].replace('.', '/'))
            .resolve(coordinate[1])
            .resolve(coordinate[2]);
    try (var files = Files.list(directory)) {
      return files
          .filter(
              path ->
                  path.getFileName().toString().startsWith(coordinate[1] + "-")
                      && path.getFileName().toString().endsWith(".jar"))
          .filter(
              path ->
                  !path.getFileName().toString().endsWith("-sources.jar")
                      && !path.getFileName().toString().endsWith("-javadoc.jar"))
          .findFirst()
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "published candidate JAR is missing from " + directory));
    }
  }

  private static void writeReport(
      Path report,
      String head,
      String status,
      boolean allowDirty,
      Path worktree,
      Path source,
      Path published,
      Path resolved,
      String coordinates)
      throws Exception {
    Files.createDirectories(report.getParent());
    String candidate =
        status.isBlank() && !allowDirty ? "PASSING CANDIDATE" : "NOT A RELEASE CANDIDATE";
    String javaVersion =
        System.getProperty("java.vendor") + " " + System.getProperty("java.version");
    String nodeVersion = output(source, "node", "--version").trim();
    String reviewInputs =
        digests(
            source,
            List.of(
                "NOTICE",
                "CHANGELOG.md",
                "req/model-fixture-policy.md",
                "req/release-checklist.md",
                "upstream/upstream-lock.json"));
    String body =
        "# Release verification\n\nStatus: "
            + candidate
            + "\n\n- Generated: "
            + Instant.now()
            + "\n- Git HEAD: `"
            + head
            + "`\n- Dirty status: `"
            + status.replace("`", "'").replace("\n", "; ")
            + "`\n- Coordinates: `"
            + coordinates
            + "`\n- JDK: `"
            + javaVersion
            + "`\n- Node: `"
            + nodeVersion
            + "`\n- Candidate worktree: `"
            + worktree
            + "`\n- Published candidate JAR SHA-256: `"
            + sha256(published)
            + "`\n- Consumer-resolved JAR SHA-256: `"
            + sha256(resolved)
            + "`\n\n## Contract inputs\n\n"
            + reviewInputs
            + "\n## Human sign-off\n\n- [ ] NOTICE, changelog, POM, Javadoc, dependency report, and corpus evidence reviewed.\n";
    Files.writeString(report, body);
    Files.writeString(
        report.resolveSibling("release-verification.json"),
        "{\n  \"status\": \""
            + json(candidate)
            + "\",\n  \"gitHead\": \""
            + json(head)
            + "\",\n  \"coordinates\": \""
            + json(coordinates)
            + "\",\n  \"worktree\": \""
            + json(worktree.toString())
            + "\"\n}\n");
  }

  private static String contractCoordinates(Path contract) throws Exception {
    Matcher match = COORDINATES.matcher(Files.readString(contract));
    if (!match.find()) throw new IllegalStateException("release contract has no coordinates");
    return match.group(1);
  }

  private static String digests(Path source, List<String> names) throws Exception {
    StringBuilder result = new StringBuilder();
    for (String name : names) {
      result
          .append("- `")
          .append(name)
          .append("`: `")
          .append(sha256(source.resolve(name)))
          .append("`\n");
    }
    return result.toString();
  }

  private static String json(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }

  private static String sha256(Path file) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }

  private static String output(Path directory, String... command) throws Exception {
    Process process =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    String text = new String(process.getInputStream().readAllBytes());
    if (process.waitFor() != 0) throw new IllegalStateException(text);
    return text;
  }

  private static void run(Path directory, String... command) throws Exception {
    Process process = new ProcessBuilder(command).directory(directory.toFile()).inheritIO().start();
    if (process.waitFor() != 0)
      throw new IllegalStateException("command failed: " + String.join(" ", command));
  }

  private static void runQuietly(Path directory, String... command) {
    try {
      run(directory, command);
    } catch (Exception ignored) {
      // Cleanup is best effort.
    }
  }

  private static void deleteTree(Path path) {
    try (var files = Files.walk(path)) {
      files
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              file -> {
                try {
                  Files.deleteIfExists(file);
                } catch (Exception ignored) {
                  // Cleanup is best effort.
                }
              });
    } catch (Exception ignored) {
      // Cleanup is best effort.
    }
  }
}
