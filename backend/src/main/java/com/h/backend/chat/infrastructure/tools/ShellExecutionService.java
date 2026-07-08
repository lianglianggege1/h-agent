package com.h.backend.chat.infrastructure.tools;

import com.h.backend.chat.infrastructure.filesystem.AssistantFileStorage;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class ShellExecutionService {

    private final AssistantFileStorage fileStorage;
    private final ShellToolProperties properties;

    public ShellExecutionService(AssistantFileStorage fileStorage, ShellToolProperties properties) {
        this.fileStorage = fileStorage;
        this.properties = properties;
    }

    public String execute(String memoryId, String command, String workingDirectory, int timeoutSeconds) {
        if (!properties.isEnabled()) {
            return "Error: Shell tool is disabled";
        }
        if (command == null || command.isBlank()) {
            return "Error: Command must not be blank";
        }
        String commandValidationError = validateCommand(command);
        if (commandValidationError != null) {
            return "Error: " + commandValidationError;
        }

        int effectiveTimeout = effectiveTimeout(timeoutSeconds);
        try {
            Path workDir = fileStorage.resolveSessionDirectory(memoryId, workingDirectory);
            ProcessBuilder processBuilder = new ProcessBuilder("sh", "-c", command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(false);
            configureEnvironment(processBuilder, workDir);

            Process process = processBuilder.start();
            boolean finished = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return formatResult(
                        "Error: Command timed out after " + effectiveTimeout + " seconds.",
                        124,
                        false
                );
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            String output = combineOutput(stdout, stderr);
            OutputLimit limited = limitOutput(output.isBlank() ? "<no output>" : output);
            return formatResult(limited.output(), process.exitValue(), limited.truncated());
        } catch (IOException ex) {
            return formatResult("Error executing command (IOException): " + ex.getMessage(), 1, false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return formatResult("Error executing command (InterruptedException): " + ex.getMessage(), 1, false);
        } catch (IllegalArgumentException ex) {
            return "Error: " + ex.getMessage();
        }
    }

    private String validateCommand(String command) {
        if (containsPathTraversal(command)) {
            return "Path traversal is not allowed in shell commands";
        }
        if (containsAbsolutePath(command)) {
            return "Absolute paths are not allowed in shell commands";
        }
        return null;
    }

    private boolean containsPathTraversal(String command) {
        int index = command.indexOf("..");
        while (index >= 0) {
            boolean beforeBoundary = index == 0 || isPathBoundary(command.charAt(index - 1));
            int nextIndex = index + 2;
            boolean afterBoundary = nextIndex == command.length() || isPathBoundary(command.charAt(nextIndex));
            if (beforeBoundary && afterBoundary) {
                return true;
            }
            index = command.indexOf("..", index + 2);
        }
        return false;
    }

    private boolean containsAbsolutePath(String command) {
        boolean tokenBoundary = true;
        for (int index = 0; index < command.length(); index++) {
            char current = command.charAt(index);
            if (current == '/') {
                return tokenBoundary;
            }
            tokenBoundary = Character.isWhitespace(current)
                    || current == '\''
                    || current == '"'
                    || current == '`'
                    || current == '('
                    || current == '<'
                    || current == '>'
                    || current == '|'
                    || current == '&'
                    || current == ';';
        }
        return false;
    }

    private boolean isPathBoundary(char value) {
        return value == '/'
                || Character.isWhitespace(value)
                || value == '\''
                || value == '"'
                || value == '`'
                || value == '('
                || value == ')'
                || value == '<'
                || value == '>'
                || value == '|'
                || value == '&'
                || value == ';';
    }

    private int effectiveTimeout(int timeoutSeconds) {
        int defaultTimeout = Math.max(1, properties.getDefaultTimeoutSeconds());
        int maxTimeout = Math.max(defaultTimeout, properties.getMaxTimeoutSeconds());
        if (timeoutSeconds <= 0) {
            return defaultTimeout;
        }
        return Math.min(timeoutSeconds, maxTimeout);
    }

    private void configureEnvironment(ProcessBuilder processBuilder, Path workDir) {
        Map<String, String> environment = processBuilder.environment();
        if (!properties.isInheritEnvironment()) {
            environment.clear();
        }
        Map<String, String> minimalEnvironment = new HashMap<>();
        minimalEnvironment.put("HOME", workDir.toString());
        minimalEnvironment.put("PWD", workDir.toString());
        minimalEnvironment.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin:/usr/sbin:/sbin"));
        minimalEnvironment.put("LANG", System.getenv().getOrDefault("LANG", "C.UTF-8"));
        environment.putAll(minimalEnvironment);
    }

    private String combineOutput(String stdout, String stderr) {
        StringBuilder output = new StringBuilder();
        if (stdout != null && !stdout.isEmpty()) {
            output.append(stdout.stripTrailing());
        }
        if (stderr != null && !stderr.isBlank()) {
            for (String line : stderr.strip().split("\n")) {
                if (!output.isEmpty()) {
                    output.append('\n');
                }
                output.append("[stderr] ").append(line);
            }
        }
        return output.toString();
    }

    private OutputLimit limitOutput(String output) {
        int maxOutputBytes = Math.max(0, properties.getMaxOutputBytes());
        if (maxOutputBytes == 0 || output.getBytes(StandardCharsets.UTF_8).length <= maxOutputBytes) {
            return new OutputLimit(output, false);
        }
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        String truncated = new String(bytes, 0, maxOutputBytes, StandardCharsets.UTF_8);
        return new OutputLimit(truncated, true);
    }

    private String formatResult(String output, int exitCode, boolean truncated) {
        StringBuilder result = new StringBuilder("Exit code: ").append(exitCode);
        if (output != null && !output.isBlank()) {
            result.append("\n\n").append(output);
        }
        if (truncated) {
            result.append("\n(output was truncated)");
        }
        return result.toString();
    }

    private record OutputLimit(String output, boolean truncated) {
    }
}
