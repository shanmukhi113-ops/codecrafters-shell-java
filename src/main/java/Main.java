import java.util.Scanner;
import java.io.File;
import java.io.PrintStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class Main {
    static String currentDirectory = System.getProperty("user.dir");
    static int jobCounter = 0;

    static class RedirectionInfo {
        File file = null;
        boolean appendOutput = false;
        boolean appendError = false;
        boolean redirectErrorOnly = false;
        List<String> cleanedTokens;
    }

    // -------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------

    static List<String> parseCommand(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '\\' && !inSingleQuote) {
                if (inDoubleQuote) {
                    if (i + 1 < input.length()) {
                        char next = input.charAt(i + 1);
                        if (next == '"' || next == '$' || next == '\\' || next == '`') {
                            currentToken.append(next);
                            i++;
                        } else {
                            currentToken.append(c);
                        }
                    } else {
                        currentToken.append(c);
                    }
                } else {
                    if (i + 1 < input.length()) {
                        currentToken.append(input.charAt(++i));
                    }
                }
            } else if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == '|' && !inSingleQuote && !inDoubleQuote) {
                if (currentToken.length() > 0) {
                    tokens.add(currentToken.toString());
                    currentToken = new StringBuilder();
                }
                tokens.add("|");
            } else if (Character.isWhitespace(c) && !inSingleQuote && !inDoubleQuote) {
                if (currentToken.length() > 0) {
                    tokens.add(currentToken.toString());
                    currentToken = new StringBuilder();
                }
            } else {
                currentToken.append(c);
            }
        }

        if (currentToken.length() > 0) {
            tokens.add(currentToken.toString());
        }

        return tokens;
    }

    static boolean isBuiltin(String cmd) {
        return cmd.equals("echo") || cmd.equals("exit") || cmd.equals("type") ||
               cmd.equals("pwd") || cmd.equals("cd") || cmd.equals("complete") || cmd.equals("jobs");
    }

    static void executeBuiltin(String command, List<String> tokens, PrintStream out, PrintStream err) {
        if (command.equals("echo")) {
            StringBuilder output = new StringBuilder();
            for (int i = 1; i < tokens.size(); i++) {
                if (i > 1) output.append(" ");
                output.append(tokens.get(i));
            }
            out.println(output.toString());
        } else if (command.equals("pwd")) {
            out.println(currentDirectory);
        } else if (command.equals("jobs")) {
            // Empty implementation for this stage: no background jobs are
            // tracked/listed yet, so this intentionally produces no output.
        } else if (command.equals("cd")) {
            if (tokens.size() < 2) return;
            String path = tokens.get(1);
            String originalPath = path;

            String homeDir = System.getenv("HOME");
            if (homeDir == null) {
                homeDir = System.getProperty("user.home");
            }

            if (path.equals("~")) {
                path = homeDir;
            } else if (path.startsWith("~/")) {
                path = homeDir + path.substring(1);
            }

            File dir;
            if (new File(path).isAbsolute()) {
                dir = new File(path);
            } else {
                dir = new File(currentDirectory, path);
            }

            if (dir.exists() && dir.isDirectory()) {
                try {
                    currentDirectory = dir.getCanonicalPath();
                } catch (Exception e) {}
            } else {
                out.println("cd: " + originalPath + ": No such file or directory");
            }
        } else if (command.equals("type")) {
            if (tokens.size() < 2) return;
            String typeCommand = tokens.get(1);
            if (isBuiltin(typeCommand)) {
                out.println(typeCommand + " is a shell builtin");
            } else {
                String resolved = findExecutable(typeCommand);
                if (resolved != null) {
                    out.println(typeCommand + " is " + resolved);
                } else {
                    out.println(typeCommand + ": not found");
                }
            }
        }
        // "complete" intentionally left as a no-op builtin for now.
    }

    /**
     * Checks whether a command exists and is runnable: returns its absolute
     * path if found, or null otherwise. Used only to decide whether a
     * command can run / to print "command not found" or for `type`. The
     * ProcessBuilder is always given the ORIGINAL bare command token so
     * argv[0] stays exactly what the user typed, and Java performs its own
     * PATH lookup internally when launching — we never overwrite element 0
     * with the resolved absolute path, since that disables Java's automatic
     * PATH search and is what caused "No such file or directory" exec
     * failures previously.
     */
    private static String findExecutable(String command) {
        if (command.contains("/")) {
            File f = new File(command).isAbsolute()
                    ? new File(command)
                    : new File(currentDirectory, command);
            return (f.exists() && f.canExecute()) ? f.getAbsolutePath() : null;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String d : pathEnv.split(File.pathSeparator)) {
                File f = new File(d, command);
                if (f.exists() && f.canExecute()) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static RedirectionInfo extractRedirection(List<String> tokens) {
        RedirectionInfo info = new RedirectionInfo();
        info.cleanedTokens = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            String op = null;
            int opIdx = -1;

            if (t.contains("2>>")) { op = "2>>"; opIdx = t.indexOf("2>>"); }
            else if (t.contains("1>>")) { op = "1>>"; opIdx = t.indexOf("1>>"); }
            else if (t.contains(">>")) { op = ">>"; opIdx = t.indexOf(">>"); }
            else if (t.contains("2>")) { op = "2>"; opIdx = t.indexOf("2>"); }
            else if (t.contains("1>")) { op = "1>"; opIdx = t.indexOf("1>"); }
            else if (t.contains(">")) { op = ">"; opIdx = t.indexOf(">"); }

            if (op != null) {
                String prefix = t.substring(0, opIdx);
                String suffix = t.substring(opIdx + op.length());

                if (!prefix.isEmpty()) {
                    info.cleanedTokens.add(prefix);
                }

                String filePath;
                if (!suffix.isEmpty()) {
                    filePath = suffix;
                } else {
                    filePath = tokens.get(++i);
                }

                File f = new File(filePath).isAbsolute() ? new File(filePath) : new File(currentDirectory, filePath);
                if (f.getParentFile() != null) {
                    f.getParentFile().mkdirs();
                }
                info.file = f;
                info.appendOutput = op.equals(">>") || op.equals("1>>");
                info.redirectErrorOnly = op.equals("2>") || op.equals("2>>");
                info.appendError = op.equals("2>>");
            } else {
                info.cleanedTokens.add(t);
            }
        }

        return info;
    }

    /** Split a flat token list into pipeline stages, separated by "|" tokens. */
    private static List<List<String>> splitIntoStages(List<String> tokens) {
        List<List<String>> stages = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String token : tokens) {
            if (token.equals("|")) {
                if (!current.isEmpty()) {
                    stages.add(current);
                    current = new ArrayList<>();
                }
            } else {
                current.add(token);
            }
        }
        if (!current.isEmpty()) {
            stages.add(current);
        }
        return stages;
    }

    // -------------------------------------------------------------------------
    // Pipeline execution
    // -------------------------------------------------------------------------

    private static void executePipeline(List<List<String>> stages) throws Exception {
        int n = stages.size();

        RedirectionInfo finalRedir = extractRedirection(stages.get(n - 1));
        stages.set(n - 1, finalRedir.cleanedTokens);

        boolean anyBuiltin = false;
        for (List<String> s : stages) {
            if (!s.isEmpty() && isBuiltin(s.get(0))) {
                anyBuiltin = true;
                break;
            }
        }

        if (!anyBuiltin) {
            runExternalPipeline(stages, finalRedir);
        } else {
            runMixedPipeline(stages, finalRedir);
        }
    }

    /**
     * All stages are external commands. Uses ProcessBuilder.startPipeline,
     * which wires every stage together with real OS pipes (kernel-managed,
     * so it streams correctly for things like `tail -f | head`), and works
     * for any number of stages without manual pump threads.
     */
    private static void runExternalPipeline(List<List<String>> stages, RedirectionInfo finalRedir) throws Exception {
        int n = stages.size();
        List<ProcessBuilder> builders = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            List<String> tokens = stages.get(i);
            if (tokens.isEmpty()) return;
            String cmd = tokens.get(0);

            if (findExecutable(cmd) == null) {
                System.out.println(cmd + ": command not found");
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(tokens); // bare cmd kept -> correct argv[0]
            pb.directory(new File(currentDirectory));
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

            if (i == 0) {
                pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            }

            if (i == n - 1) {
                if (finalRedir.file != null && !finalRedir.redirectErrorOnly) {
                    pb.redirectOutput(finalRedir.appendOutput
                            ? ProcessBuilder.Redirect.appendTo(finalRedir.file)
                            : ProcessBuilder.Redirect.to(finalRedir.file));
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }
                if (finalRedir.file != null && finalRedir.redirectErrorOnly) {
                    pb.redirectError(finalRedir.appendError
                            ? ProcessBuilder.Redirect.appendTo(finalRedir.file)
                            : ProcessBuilder.Redirect.to(finalRedir.file));
                }
            }

            builders.add(pb);
        }

        List<Process> processes = ProcessBuilder.startPipeline(builders);
        for (Process p : processes) {
            p.waitFor();
        }
    }

    /**
     * At least one stage is a builtin. Runs stages sequentially, buffering
     * each stage's output fully in memory and feeding it into the next
     * stage's stdin (or System.in if the next stage is itself a builtin).
     */
    private static void runMixedPipeline(List<List<String>> stages, RedirectionInfo finalRedir) throws Exception {
        int n = stages.size();
        byte[] inputBytes = null;

        for (int i = 0; i < n; i++) {
            List<String> tokens = stages.get(i);
            if (tokens.isEmpty()) return;
            String cmd = tokens.get(0);
            boolean last = (i == n - 1);

            if (isBuiltin(cmd)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                PrintStream savedOut = System.out;
                PrintStream target;
                FileOutputStream fileOut = null;

                if (last) {
                    if (finalRedir.file != null && !finalRedir.redirectErrorOnly) {
                        fileOut = new FileOutputStream(finalRedir.file, finalRedir.appendOutput);
                        target = new PrintStream(fileOut);
                    } else {
                        target = savedOut;
                    }
                } else {
                    target = new PrintStream(baos);
                }

                try {
                    executeBuiltin(cmd, tokens, target, System.err);
                    target.flush();
                } finally {
                    if (fileOut != null) fileOut.close();
                }

                inputBytes = last ? null : baos.toByteArray();

            } else {
                if (findExecutable(cmd) == null) {
                    System.out.println(cmd + ": command not found");
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder(tokens);
                pb.directory(new File(currentDirectory));
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                pb.redirectInput(i == 0 ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE);

                if (last) {
                    if (finalRedir.file != null && !finalRedir.redirectErrorOnly) {
                        pb.redirectOutput(finalRedir.appendOutput
                                ? ProcessBuilder.Redirect.appendTo(finalRedir.file)
                                : ProcessBuilder.Redirect.to(finalRedir.file));
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }
                    if (finalRedir.file != null && finalRedir.redirectErrorOnly) {
                        pb.redirectError(finalRedir.appendError
                                ? ProcessBuilder.Redirect.appendTo(finalRedir.file)
                                : ProcessBuilder.Redirect.to(finalRedir.file));
                    }
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                }

                Process p = pb.start();

                if (i > 0) {
                    try (OutputStream os = p.getOutputStream()) {
                        if (inputBytes != null) os.write(inputBytes);
                    }
                }

                if (!last) {
                    inputBytes = p.getInputStream().readAllBytes();
                }

                p.waitFor();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Main loop
    // -------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            System.out.flush();

            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine();
            List<String> tokens = parseCommand(input);

            if (tokens.isEmpty()) continue;
            if (tokens.get(0).equals("exit")) break;

            // Detect trailing "&" -> run in background (single external
            // commands only, for now; pipelines ignore this flag).
            boolean background = false;
            if (tokens.get(tokens.size() - 1).equals("&")) {
                background = true;
                tokens.remove(tokens.size() - 1);
            }
            if (tokens.isEmpty()) continue;

            List<List<String>> pipelineStages = splitIntoStages(tokens);

            if (pipelineStages.size() > 1) {
                executePipeline(pipelineStages);
            } else {
                RedirectionInfo rInfo = extractRedirection(tokens);
                List<String> cmdTokens = rInfo.cleanedTokens;
                if (cmdTokens.isEmpty()) continue;
                String command = cmdTokens.get(0);

                if (isBuiltin(command)) {
                    PrintStream outStream = System.out;
                    PrintStream errStream = System.err;
                    PrintStream fileStream = null;

                    if (rInfo.file != null) {
                        fileStream = new PrintStream(new FileOutputStream(
                                rInfo.file, rInfo.redirectErrorOnly ? rInfo.appendError : rInfo.appendOutput));
                        if (rInfo.redirectErrorOnly) {
                            errStream = fileStream;
                        } else {
                            outStream = fileStream;
                        }
                    }

                    executeBuiltin(command, cmdTokens, outStream, errStream);
                    if (fileStream != null) fileStream.close();
                } else {
                    String resolved = findExecutable(command);
                    if (resolved == null) {
                        System.out.println(command + ": command not found");
                    } else {
                        ProcessBuilder pb = new ProcessBuilder(cmdTokens); // bare cmd -> correct argv[0]
                        pb.directory(new File(currentDirectory));
                        pb.inheritIO();

                        if (rInfo.file != null) {
                            if (rInfo.redirectErrorOnly) {
                                pb.redirectError(rInfo.appendError
                                        ? ProcessBuilder.Redirect.appendTo(rInfo.file)
                                        : ProcessBuilder.Redirect.to(rInfo.file));
                            } else {
                                pb.redirectOutput(rInfo.appendOutput
                                        ? ProcessBuilder.Redirect.appendTo(rInfo.file)
                                        : ProcessBuilder.Redirect.to(rInfo.file));
                            }
                        }

                        Process process = pb.start();

                        if (background) {
                            jobCounter++;
                            System.out.println("[" + jobCounter + "] " + process.pid());
                        } else {
                            process.waitFor();
                        }
                    }
                }
            }
        }
    }
}