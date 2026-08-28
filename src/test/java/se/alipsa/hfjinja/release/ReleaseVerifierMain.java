package se.alipsa.hfjinja.release;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Creates an isolated detached worktree and runs the release candidate matrix. */
public final class ReleaseVerifierMain {
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
    Path worktree = Files.createTempDirectory("hfjinja-release-worktree-");
    Files.delete(worktree);
    Path report = source.resolve("build/reports/release-verification.md");
    try {
      run(source, "git", "worktree", "add", "--detach", worktree.toString(), head);
      Files.createDirectories(userHome);
      // Preparation intentionally resolves the same build once online into the isolated user home.
      gradle(worktree, userHome, false, "verifyReproducibleArchives", "check", "dependencyUpdates");
      Path stagedReport =
          Files.createTempDirectory("hfjinja-dependency-evidence-")
              .resolve("dependency-updates.txt");
      Files.copy(worktree.resolve("build/dependencyUpdates/report.txt"), stagedReport);
      gradle(
          worktree,
          userHome,
          true,
          "verifyReproducibleArchives",
          "clean",
          "check",
          "dependencyReview",
          "-PreleaseVerificationDependencyReport=" + stagedReport);
      Path repository = Files.createTempDirectory("hfjinja-release-repository-");
      gradle(
          worktree,
          userHome,
          true,
          "publishMavenPublicationToReleaseVerificationRepository",
          "-PreleaseVerificationRepository=" + repository);
      Path resolved = runConsumer(worktree, userHome, repository);
      Path published =
          repository.resolve("se/alipsa/hfjinja/0.5.0-SNAPSHOT/hfjinja-0.5.0-SNAPSHOT.jar");
      if (!sha256(published).equals(sha256(resolved))) {
        throw new IllegalStateException(
            "consumer resolved artifact does not match the published candidate JAR");
      }
      writeReport(report, head, status, allowDirty, worktree, published, resolved);
    } finally {
      runQuietly(source, "git", "worktree", "remove", "--force", worktree.toString());
      runQuietly(source, "git", "worktree", "prune");
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
    run(project, command.toArray(String[]::new));
  }

  private static Path runConsumer(Path candidate, Path userHome, Path repository) throws Exception {
    Path consumer = Files.createTempDirectory("hfjinja-release-consumer-");
    Files.writeString(
        consumer.resolve("settings.gradle"),
        "pluginManagement { repositories { } }\nrootProject.name = 'hfjinja-release-consumer'\n");
    String repositoryUri = repository.toUri().toString().replace("'", "\\'");
    Files.writeString(
        consumer.resolve("build.gradle"),
        """
        plugins { id 'java' }
        repositories { maven { url = uri('%s') } }
        dependencies { implementation 'se.alipsa:hfjinja:0.5.0-SNAPSHOT' }
        java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
        tasks.register('consumerVerify', JavaExec) {
          dependsOn tasks.named('classes')
          classpath = sourceSets.main.runtimeClasspath
          mainClass = 'consumer.ConsumerMain'
        }
        tasks.register('copyResolvedCandidate', Copy) {
          from configurations.runtimeClasspath
          include 'hfjinja-0.5.0-SNAPSHOT.jar'
          into layout.buildDirectory.dir('resolved')
        }
        tasks.named('consumerVerify') { finalizedBy tasks.named('copyResolvedCandidate') }
        """
            .formatted(repositoryUri));
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
          .filter(path -> path.getFileName().toString().equals("hfjinja-0.5.0-SNAPSHOT.jar"))
          .findFirst()
          .orElseThrow(
              () -> new IllegalStateException("consumer did not resolve the candidate JAR"));
    }
  }

  private static void writeReport(
      Path report,
      String head,
      String status,
      boolean allowDirty,
      Path worktree,
      Path published,
      Path resolved)
      throws Exception {
    Files.createDirectories(report.getParent());
    String candidate = status.isBlank() ? "PASSING CANDIDATE" : "NOT A RELEASE CANDIDATE";
    Files.writeString(
        report,
        "# Release verification\n\nStatus: "
            + candidate
            + "\n\n"
            + "- Generated: "
            + Instant.now()
            + "\n- Git HEAD: `"
            + head
            + "`\n- Dirty status: `"
            + status.replace("`", "'").replace("\n", "; ")
            + "`\n- Candidate worktree: `"
            + worktree
            + "`\n"
            + "- Contract: `req/release-verification.json`\n- Published candidate JAR SHA-256: `"
            + sha256(published)
            + "`\n- Consumer-resolved JAR SHA-256: `"
            + sha256(resolved)
            + "`\n- Archive, POM, corpus, and dependency facts were generated in the detached worktree.\n");
    Files.writeString(
        report.resolveSibling("release-verification.json"),
        "{\n  \"status\": \""
            + candidate
            + "\",\n  \"gitHead\": \""
            + head
            + "\",\n  \"worktree\": \""
            + worktree
            + "\"\n}\n");
    if (allowDirty && status.isBlank())
      throw new IllegalStateException("--allow-dirty must not alter a clean result");
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
      /* cleanup best effort */
    }
  }
}
