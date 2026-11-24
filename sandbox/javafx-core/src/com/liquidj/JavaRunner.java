package com.liquidj;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compiles a project with javac and runs one class with java, both as child processes.
 * Listener methods are called on background threads; the caller moves results to its UI thread.
 */
final class JavaRunner {

    enum Channel { OUT, ERR, INFO }

    enum Status { COMPILED, RAN, COMPILE_FAILED, STOPPED, FAILED_TO_START }

    record Diagnostic(Path file, int line, boolean error, String message) {}

    record Result(Status status, int exitCode, long compileMillis, List<Diagnostic> diagnostics, String message) {}

    interface Listener {
        void output(Channel channel, String text);

        void finished(Result result);
    }

    // javac prints "src\Main.java:5: error: ';' expected" followed by the source line and a caret line.
    private static final Pattern DIAGNOSTIC =
            Pattern.compile("^(.+\\.java):(\\d+): (error|warning): (.+)$", Pattern.MULTILINE);
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern MAIN_METHOD = Pattern.compile("\\bstatic\\s+void\\s+main\\s*\\(");

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile boolean stopRequested;
    private volatile Process process;
    private volatile Writer programInput;

    boolean isBusy() {
        return busy.get();
    }

    /** Starts a build. With a null mainClass it only compiles. */
    void start(Path projectDir, String mainClass, Listener listener) {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("A build is already running");
        }
        stopRequested = false;
        Thread worker = new Thread(() -> {
            Result result = build(projectDir, mainClass, listener);
            busy.set(false);
            listener.finished(result);
        }, "liquidj-build");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        stopRequested = true;
        Process running = process;
        if (running != null) {
            running.destroyForcibly();
        }
    }

    /** Sends one line to the running program's standard input. Returns false when no program is reading. */
    boolean sendInput(String line) {
        Writer input = programInput;
        if (input == null) {
            return false;
        }
        try {
            input.write(line);
            input.write(System.lineSeparator());
            input.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static Path sourceRoot(Path projectDir) {
        Path src = projectDir.resolve("src");
        return Files.isDirectory(src) ? src : projectDir;
    }

    /** The fully qualified class to run for this file, if the file declares a main method. */
    static Optional<String> mainClassOf(Path file) {
        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(".java")) {
            return Optional.empty();
        }
        String code;
        try {
            code = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Optional.empty();
        }
        String bare = JavaSyntax.maskCommentsAndLiterals(code);
        if (!MAIN_METHOD.matcher(bare).find()) {
            return Optional.empty();
        }
        String simpleName = fileName.substring(0, fileName.length() - ".java".length());
        Matcher pkg = PACKAGE.matcher(bare);
        return Optional.of(pkg.find() ? pkg.group(1) + "." + simpleName : simpleName);
    }

    static List<String> mainClasses(Path projectDir) {
        try {
            return javaFiles(projectDir).stream()
                    .map(JavaRunner::mainClassOf)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private Result build(Path projectDir, String mainClass, Listener listener) {
        Path outDir = projectDir.resolve("out");
        long compileMillis = 0;
        try {
            List<Path> sources = javaFiles(projectDir);
            if (sources.isEmpty()) {
                return failedToStart("No .java files found in " + sourceRoot(projectDir) + ".");
            }
            Files.createDirectories(outDir);

            List<String> javac = new ArrayList<>(List.of(tool("javac"), "-encoding", "UTF-8",
                    "-J-Dstdout.encoding=UTF-8", "-J-Dstderr.encoding=UTF-8", "-d", "out"));
            sources.forEach(source -> javac.add(projectDir.relativize(source).toString()));
            listener.output(Channel.INFO, "javac -d out (" + plural(sources.size(), "file") + ")\n");

            long started = System.nanoTime();
            StringBuilder compilerText = new StringBuilder();
            int javacExit = runProcess(javac, projectDir, listener, compilerText, false);
            compileMillis = (System.nanoTime() - started) / 1_000_000;
            if (stopRequested) {
                return stopped(compileMillis);
            }

            List<Diagnostic> diagnostics = parseDiagnostics(compilerText.toString(), projectDir);
            if (javacExit != 0) {
                return new Result(Status.COMPILE_FAILED, javacExit, compileMillis, diagnostics, null);
            }
            if (mainClass == null) {
                return new Result(Status.COMPILED, 0, compileMillis, diagnostics, null);
            }

            listener.output(Channel.INFO, "java -cp out " + mainClass + "\n");
            List<String> java = List.of(tool("java"), "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                    "-cp", "out", mainClass);
            int exitCode = runProcess(java, projectDir, listener, null, true);
            if (stopRequested) {
                return stopped(compileMillis);
            }
            return new Result(Status.RAN, exitCode, compileMillis, diagnostics, null);
        } catch (IOException e) {
            return failedToStart(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return stopped(compileMillis);
        }
    }

    private int runProcess(List<String> command, Path workingDir, Listener listener,
                           StringBuilder capture, boolean acceptsInput) throws IOException, InterruptedException {
        Process started;
        try {
            started = new ProcessBuilder(command).directory(workingDir.toFile()).start();
        } catch (IOException e) {
            throw new IOException("Could not start " + Path.of(command.get(0)).getFileName()
                    + ". Liquid-J needs a JDK (21 or newer) that includes javac and java.", e);
        }
        process = started;
        if (stopRequested) {
            // stop() ran between the stopRequested check and process creation.
            started.destroyForcibly();
        }
        if (acceptsInput) {
            programInput = new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8);
        }
        Thread out = pump(started.getInputStream(), Channel.OUT, listener, capture);
        Thread err = pump(started.getErrorStream(), Channel.ERR, listener, capture);
        try {
            int exitCode = started.waitFor();
            out.join();
            err.join();
            return exitCode;
        } catch (InterruptedException e) {
            started.destroyForcibly();
            throw e;
        } finally {
            programInput = null;
            process = null;
        }
    }

    /** Reads a stream in chunks, not lines, so prompts printed without a newline still show up. */
    private static Thread pump(InputStream stream, Channel channel, Listener listener, StringBuilder capture) {
        Thread reader = new Thread(() -> {
            char[] buffer = new char[4096];
            try (Reader in = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    String text = new String(buffer, 0, count);
                    if (capture != null) {
                        synchronized (capture) {
                            capture.append(text);
                        }
                    }
                    listener.output(channel, text);
                }
            } catch (IOException e) {
                // The pipe closes when the process is killed; there is nothing left to read.
            }
        }, "liquidj-" + channel.name().toLowerCase());
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private static List<Path> javaFiles(Path projectDir) throws IOException {
        Path outDir = projectDir.resolve("out");
        try (Stream<Path> walk = Files.walk(sourceRoot(projectDir))) {
            return walk.filter(path -> !path.startsWith(outDir))
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        }
    }

    private static List<Diagnostic> parseDiagnostics(String compilerText, Path projectDir) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Matcher match = DIAGNOSTIC.matcher(compilerText);
        while (match.find()) {
            diagnostics.add(new Diagnostic(
                    projectDir.resolve(match.group(1).trim()).normalize(),
                    Integer.parseInt(match.group(2)),
                    match.group(3).equals("error"),
                    match.group(4).trim()));
        }
        return diagnostics;
    }

    /** Prefers the javac/java next to the JVM running the IDE, then falls back to PATH. */
    private static String tool(String name) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? name + ".exe" : name;
        Path bundled = Path.of(System.getProperty("java.home"), "bin", executable);
        return Files.isExecutable(bundled) ? bundled.toString() : name;
    }

    private static Result failedToStart(String message) {
        return new Result(Status.FAILED_TO_START, -1, 0, List.of(), message);
    }

    private static Result stopped(long compileMillis) {
        return new Result(Status.STOPPED, -1, compileMillis, List.of(), null);
    }

    static String plural(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
