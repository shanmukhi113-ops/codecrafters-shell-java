import java.util.*;
import java.io.*;

public class Main {
    public static void main(String[] args) throws Exception {
        String[] rawCmd = {"/bin/sh", "-c", "stty raw -echo </dev/tty"};
        Runtime.getRuntime().exec(rawCmd).waitFor();

        InputStream in = System.in;
        StringBuilder currentLine = new StringBuilder();
        List<String> builtins = Arrays.asList("echo", "exit", "type", "pwd", "cd");

        try {
            System.out.print("$ ");
            while (true) {
                int read = in.read();
                if (read == -1 || read == 4) break;

                if (read == 9) { // TAB key
                    String currentInput = currentLine.toString();
                    int lastSpaceIdx = currentInput.lastIndexOf(' ');
                    String prefix = (lastSpaceIdx == -1) ? currentInput : currentInput.substring(lastSpaceIdx + 1);

                    List<String> matches = new ArrayList<>();

                    if (lastSpaceIdx == -1) {
                        for (String b : builtins) {
                            if (b.startsWith(prefix)) matches.add(b);
                        }
                    }

                    File currentDir = new File(System.getProperty("user.dir"));
                    File[] files = currentDir.listFiles();
                    if (files != null) {
                        List<String> fileMatches = new ArrayList<>();
                        for (File f : files) {
                            if (f.getName().startsWith(prefix)) fileMatches.add(f.getName());
                        }
                        Collections.sort(fileMatches);
                        matches.addAll(fileMatches);
                    }

                    if (matches.size() == 1) {
                        String match = matches.get(0);
                        String completed = match.substring(prefix.length()) + " ";
                        System.out.print(completed);
                        currentLine.append(completed);
                    } else {
                        System.out.print("\u0007");
                    }
                } else if (read == 13 || read == 10) { // Enter
                    System.out.print("\r\n");
                    String input = currentLine.toString().trim();
                    currentLine.setLength(0);

                    String[] cookedCmd = {"/bin/sh", "-c", "stty cooked echo </dev/tty"};
                    Runtime.getRuntime().exec(cookedCmd).waitFor();

                    while (in.available() > 0) {
                        int nextByte = in.read();
                        if (nextByte != 10 && nextByte != 13) break;
                    }

                    if (!input.isEmpty()) {
                        executeCommand(input);
                    }

                    Runtime.getRuntime().exec(rawCmd).waitFor();
                    System.out.print("$ ");
                } else if (read == 127 || read == 8) { // Backspace
                    if (currentLine.length() > 0) {
                        currentLine.deleteCharAt(currentLine.length() - 1);
                        System.out.print("\b \b");
                    }
                } else {
                    if (read >= 32 && read < 127) {
                        char c = (char) read;
                        currentLine.append(c);
                        System.out.print(c);
                    }
                }
            }
        } finally {
            String[] cookedCmd = {"/bin/sh", "-c", "stty cooked echo </dev/tty"};
            Runtime.getRuntime().exec(cookedCmd).waitFor();
        }
    }

    // Split raw input by | that are outside of single/double quotes
    private static List<String> splitByPipe(String input) {
        List<String> parts = new ArrayList<>();
        boolean inSingle = false, inDouble = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(c);
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(c);
            } else if (c == '|' && !inSingle && !inDouble) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    // Execute a two-command pipeline: cmd1 | cmd2
    private static void executePipeline(List<String> parts) throws Exception {
        List<String> leftTokens  = parseArguments(parts.get(0).trim());
        List<String> rightTokens = parseArguments(parts.get(1).trim());

        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return;

        // Parse optional output redirection from the right-hand side
        String redirectFile = null;
        boolean appendOut = false;
        List<String> rightCmd = new ArrayList<>();
        for (int i = 0; i < rightTokens.size(); i++) {
            String t = rightTokens.get(i);
            if ((t.equals(">") || t.equals("1>")) && i + 1 < rightTokens.size()) {
                redirectFile = rightTokens.get(++i); appendOut = false;
            } else if ((t.equals(">>") || t.equals("1>>")) && i + 1 < rightTokens.size()) {
                redirectFile = rightTokens.get(++i); appendOut = true;
            } else {
                rightCmd.add(t);
            }
        }
        if (rightCmd.isEmpty()) return;

        // Left process: stdout flows to us via getInputStream()
        ProcessBuilder pb1 = new ProcessBuilder(leftTokens);
        pb1.redirectError(ProcessBuilder.Redirect.INHERIT);

        // Right process: stdout goes to terminal (or file), stdin fed by pump thread
        ProcessBuilder pb2 = new ProcessBuilder(rightCmd);
        pb2.redirectError(ProcessBuilder.Redirect.INHERIT);
        if (redirectFile != null) {
            File f = new File(redirectFile);
            pb2.redirectOutput(appendOut
                    ? ProcessBuilder.Redirect.appendTo(f)
                    : ProcessBuilder.Redirect.to(f));
        } else {
            pb2.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        }

        Process p1 = pb1.start();
        Process p2 = pb2.start();

        // Pump p1 stdout -> p2 stdin on a background thread.
        // Running this in a separate thread is essential for streaming commands
        // like `tail -f` so neither side blocks waiting for the other.
        Thread pump = new Thread(() -> {
            try (InputStream from = p1.getInputStream();
                 OutputStream to   = p2.getOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = from.read(buf)) != -1) {
                    to.write(buf, 0, n);
                    to.flush();
                }
            } catch (IOException ignored) {}
        });
        pump.setDaemon(true);
        pump.start();

        p1.waitFor();
        pump.join();
        p2.waitFor();
    }

    private static void executeCommand(String input) throws Exception {
        // Detect pipeline before anything else
        List<String> pipelineParts = splitByPipe(input);
        if (pipelineParts.size() >= 2) {
            executePipeline(pipelineParts);
            return;
        }

        List<String> rawTokens = parseArguments(input);
        if (rawTokens.isEmpty()) return;

        String redirectFile = null;
        String redirectErrFile = null;
        boolean appendOut = false;
        boolean appendErr = false;
        List<String> tokens = new ArrayList<>();

        for (int i = 0; i < rawTokens.size(); i++) {
            String token = rawTokens.get(i);
            if (token.equals(">") || token.equals("1>")) {
                if (i + 1 < rawTokens.size()) { redirectFile = rawTokens.get(++i); appendOut = false; }
            } else if (token.equals(">>") || token.equals("1>>")) {
                if (i + 1 < rawTokens.size()) { redirectFile = rawTokens.get(++i); appendOut = true; }
            } else if (token.equals("2>")) {
                if (i + 1 < rawTokens.size()) { redirectErrFile = rawTokens.get(++i); appendErr = false; }
            } else if (token.equals("2>>")) {
                if (i + 1 < rawTokens.size()) { redirectErrFile = rawTokens.get(++i); appendErr = true; }
            } else {
                tokens.add(token);
            }
        }

        if (tokens.isEmpty()) return;
        String command = tokens.get(0);

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        FileOutputStream fosOut = null;
        PrintStream psOut = null;
        FileOutputStream fosErr = null;
        PrintStream psErr = null;

        if (redirectFile != null) {
            try {
                File outFile = new File(redirectFile);
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                fosOut = new FileOutputStream(outFile, appendOut);
                psOut = new PrintStream(fosOut);
                System.setOut(psOut);
            } catch (Exception e) { System.setOut(originalOut); }
        }

        if (redirectErrFile != null) {
            try {
                File errFile = new File(redirectErrFile);
                File parent = errFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                fosErr = new FileOutputStream(errFile, appendErr);
                psErr = new PrintStream(fosErr);
                System.setErr(psErr);
            } catch (Exception e) { System.setErr(originalErr); }
        }

        try {
            if (command.equals("exit")) {
                int status = 0;
                if (tokens.size() > 1) {
                    try { status = Integer.parseInt(tokens.get(1)); } catch (NumberFormatException e) { status = 0; }
                }
                String[] cookedCmd = {"/bin/sh", "-c", "stty cooked echo </dev/tty"};
                Runtime.getRuntime().exec(cookedCmd).waitFor();
                System.exit(status);
            } else if (command.equals("echo")) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < tokens.size(); i++) {
                    sb.append(tokens.get(i));
                    if (i < tokens.size() - 1) sb.append(" ");
                }
                System.out.println(sb.toString());
            } else if (command.equals("type")) {
                if (tokens.size() < 2) {
                    System.err.println("type: missing operand");
                } else {
                    String target = tokens.get(1);
                    if (Arrays.asList("echo", "exit", "type", "pwd", "cd").contains(target)) {
                        System.out.println(target + " is a shell builtin");
                    } else {
                        String path = getPath(target);
                        if (path != null) System.out.println(target + " is " + path);
                        else System.out.println(target + ": not found");
                    }
                }
            } else if (command.equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
            } else if (command.equals("cd")) {
                String targetDir = tokens.size() > 1 ? tokens.get(1) : "~";
                if (targetDir.equals("~")) {
                    String homeDir = System.getenv("HOME");
                    if (homeDir != null) System.setProperty("user.dir", homeDir);
                } else {
                    File dir = new File(targetDir);
                    if (!dir.isAbsolute()) dir = new File(System.getProperty("user.dir"), targetDir);
                    try {
                        dir = dir.getCanonicalFile();
                        if (dir.exists() && dir.isDirectory()) System.setProperty("user.dir", dir.getAbsolutePath());
                        else System.out.println("cd: " + targetDir + ": No such file or directory");
                    } catch (IOException e) {
                        System.out.println("cd: " + targetDir + ": No such file or directory");
                    }
                }
            } else {
                try {
                    List<String> cmdArgs = new ArrayList<>();
                    cmdArgs.add(command); // bare name -> argv[0]
                    for (int i = 1; i < tokens.size(); i++) cmdArgs.add(tokens.get(i));

                    ProcessBuilder pb = new ProcessBuilder(cmdArgs);

                    if (redirectFile != null) {
                        File f = new File(redirectFile);
                        pb.redirectOutput(appendOut ? ProcessBuilder.Redirect.appendTo(f) : ProcessBuilder.Redirect.to(f));
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }

                    if (redirectErrFile != null) {
                        File fErr = new File(redirectErrFile);
                        pb.redirectError(appendErr ? ProcessBuilder.Redirect.appendTo(fErr) : ProcessBuilder.Redirect.to(fErr));
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }

                    Process p = pb.start();
                    p.waitFor();
                } catch (Exception e) {
                    System.setOut(originalOut);
                    System.setErr(originalErr);
                    System.err.println(command + ": command not found");
                }
            }
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            if (psOut != null) psOut.close();
            if (fosOut != null) fosOut.close();
            if (psErr != null) psErr.close();
            if (fosErr != null) fosErr.close();
        }
    }

    private static List<String> parseArguments(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean inSingleQuotes = false, inDoubleQuotes = false, hasChars = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inSingleQuotes) {
                if (c == '\'') inSingleQuotes = false;
                else { currentToken.append(c); hasChars = true; }
            } else if (inDoubleQuotes) {
                if (c == '"') {
                    inDoubleQuotes = false;
                } else if (c == '\\' && i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '\\' || next == '"' || next == '$' || next == '`') {
                        currentToken.append(next); i++;
                    } else {
                        currentToken.append(c);
                    }
                    hasChars = true;
                } else { currentToken.append(c); hasChars = true; }
            } else {
                if (c == '\'') { inSingleQuotes = true; hasChars = true; }
                else if (c == '"') { inDoubleQuotes = true; hasChars = true; }
                else if (c == '\\' && i + 1 < input.length()) {
                    currentToken.append(input.charAt(++i)); hasChars = true;
                } else if (Character.isWhitespace(c)) {
                    if (hasChars) { tokens.add(currentToken.toString()); currentToken.setLength(0); hasChars = false; }
                } else { currentToken.append(c); hasChars = true; }
            }
        }
        if (hasChars) tokens.add(currentToken.toString());
        return tokens;
    }

    private static String getPath(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String p : pathEnv.split(File.pathSeparator)) {
            File file = new File(p, command);
            if (file.exists() && file.isFile() && file.canExecute()) return file.getAbsolutePath();
        }
        return null;
    }
}