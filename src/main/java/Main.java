import java.util.*;
import java.io.*;

public class Main {
    public static void main(String[] args) throws Exception {
        // Configure terminal to raw mode to capture individual keystrokes like TAB
        String[] rawCmd = {"/bin/sh", "-c", "stty raw -echo </dev/tty"};
        Runtime.getRuntime().exec(rawCmd).waitFor();

        InputStream in = System.in;
        StringBuilder currentLine = new StringBuilder();
        List<String> builtins = Arrays.asList("echo", "exit", "type", "pwd", "cd");

        try {
            System.out.print("$ ");
            while (true) {
                int read = in.read();
                if (read == -1 || read == 4) { // Ctrl+D or EOF
                    break;
                }

                if (read == 9) { // TAB key
                    String currentInput = currentLine.toString();

                    // Find text segment after the last space to use as completion prefix
                    int lastSpaceIdx = currentInput.lastIndexOf(' ');
                    String prefix = (lastSpaceIdx == -1) ? currentInput : currentInput.substring(lastSpaceIdx + 1);

                    List<String> matches = new ArrayList<>();

                    // Step 1: Check built-in shell commands first if it's the first word
                    if (lastSpaceIdx == -1) {
                        for (String b : builtins) {
                            if (b.startsWith(prefix)) {
                                matches.add(b);
                            }
                        }
                    }

                    // Step 2: Check matching files in the current working directory
                    File currentDir = new File(System.getProperty("user.dir"));
                    File[] files = currentDir.listFiles();
                    if (files != null) {
                        List<String> fileMatches = new ArrayList<>();
                        for (File f : files) {
                            if (f.getName().startsWith(prefix)) {
                                fileMatches.add(f.getName());
                            }
                        }
                        Collections.sort(fileMatches);
                        matches.addAll(fileMatches);
                    }

                    // Step 3: Complete only if there is an unambiguous single match
                    if (matches.size() == 1) {
                        String match = matches.get(0);
                        String completed = match.substring(prefix.length()) + " ";
                        System.out.print(completed);
                        currentLine.append(completed);
                    } else {
                        System.out.print("\u0007"); // Alert bell
                    }
                } else if (read == 13 || read == 10) { // Enter key handling
                    System.out.print("\r\n");
                    String input = currentLine.toString().trim();
                    currentLine.setLength(0);

                    // Drop into cooked terminal mode before running execution logic
                    String[] cookedCmd = {"/bin/sh", "-c", "stty cooked echo </dev/tty"};
                    Runtime.getRuntime().exec(cookedCmd).waitFor();

                    // Consume any lingering newline bytes (\n following \r) to prevent line corruption
                    while (in.available() > 0) {
                        int nextByte = in.read();
                        if (nextByte != 10 && nextByte != 13) {
                            // If it's a real character character code, put it back or save it (unlikely here)
                            break;
                        }
                    }

                    if (!input.isEmpty()) {
                        executeCommand(input);
                    }

                    // Re-enable raw mode for next user line input sequence
                    Runtime.getRuntime().exec(rawCmd).waitFor();
                    System.out.print("$ ");
                } else if (read == 127 || read == 8) { // Backspace
                    if (currentLine.length() > 0) {
                        currentLine.deleteCharAt(currentLine.length() - 1);
                        System.out.print("\b \b");
                    }
                } else {
                    // Restrict characters entering buffer to standard printable ASCII values
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

    private static void executeCommand(String input) throws Exception {
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
                if (i + 1 < rawTokens.size()) {
                    redirectFile = rawTokens.get(i + 1);
                    appendOut = false;
                    break;
                }
            } else if (token.equals(">>") || token.equals("1>>")) {
                if (i + 1 < rawTokens.size()) {
                    redirectFile = rawTokens.get(i + 1);
                    appendOut = true;
                    break;
                }
            } else if (token.equals("2>")) {
                if (i + 1 < rawTokens.size()) {
                    redirectErrFile = rawTokens.get(i + 1);
                    appendErr = false;
                    break;
                }
            } else if (token.equals("2>>")) {
                if (i + 1 < rawTokens.size()) {
                    redirectErrFile = rawTokens.get(i + 1);
                    appendErr = true;
                    break;
                }
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
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                fosOut = new FileOutputStream(outFile, appendOut);
                psOut = new PrintStream(fosOut);
                System.setOut(psOut);
            } catch (Exception e) {
                System.setOut(originalOut);
            }
        }

        if (redirectErrFile != null) {
            try {
                File errFile = new File(redirectErrFile);
                File parent = errFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                fosErr = new FileOutputStream(errFile, appendErr);
                psErr = new PrintStream(fosErr);
                System.setErr(psErr);
            } catch (Exception e) {
                System.setErr(originalErr);
            }
        }

        try {
            if (command.equals("exit")) {
                int status = 0;
                if (tokens.size() > 1) {
                    try {
                        status = Integer.parseInt(tokens.get(1));
                    } catch (NumberFormatException e) {
                        status = 0;
                    }
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
                    if (target.equals("echo") || target.equals("exit") || target.equals("type") || target.equals("pwd") || target.equals("cd")) {
                        System.out.println(target + " is a shell builtin");
                    } else {
                        String path = getPath(target);
                        if (path != null) {
                            System.out.println(target + " is " + path);
                        } else {
                            System.out.println(target + ": not found");
                        }
                    }
                }
            } else if (command.equals("pwd")) {
                System.out.println(System.getProperty("user.dir"));
            } else if (command.equals("cd")) {
                String targetDir = "~";
                if (tokens.size() > 1) {
                    targetDir = tokens.get(1);
                }

                if (targetDir.equals("~")) {
                    String homeDir = System.getenv("HOME");
                    if (homeDir != null) {
                        System.setProperty("user.dir", homeDir);
                    }
                } else {
                    File dir = new File(targetDir);
                    if (!dir.isAbsolute()) {
                        dir = new File(System.getProperty("user.dir"), targetDir);
                    }
                    try {
                        dir = dir.getCanonicalFile();
                        if (dir.exists() && dir.isDirectory()) {
                            System.setProperty("user.dir", dir.getAbsolutePath());
                        } else {
                            System.out.println("cd: " + targetDir + ": No such file or directory");
                        }
                    } catch (IOException e) {
                        System.out.println("cd: " + targetDir + ": No such file or directory");
                    }
                }
            } else {
                try {
                    List<String> cmdArgs = new ArrayList<>();
                    // command stays as the bare typed name -> this becomes argv[0]
                    // for the child process exactly as the user typed it.
                    cmdArgs.add(command);
                    for (int i = 1; i < tokens.size(); i++) {
                        cmdArgs.add(tokens.get(i));
                    }

                    ProcessBuilder pb = new ProcessBuilder(cmdArgs);
                    // NOTE: we deliberately do NOT call pb.command().set(0, path) here.
                    // ProcessBuilder resolves a bare (no "/") executable name via PATH
                    // on its own, while still passing argv[0] through unchanged as
                    // exactly what was typed (e.g. "custom_exe_2375" instead of the
                    // resolved absolute path).

                    if (redirectFile != null) {
                        File f = new File(redirectFile);
                        if (appendOut) {
                            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(f));
                        } else {
                            pb.redirectOutput(f);
                        }
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }

                    if (redirectErrFile != null) {
                        File fErr = new File(redirectErrFile);
                        if (appendErr) {
                            pb.redirectError(ProcessBuilder.Redirect.appendTo(fErr));
                        } else {
                            pb.redirectError(fErr);
                        }
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
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean hasChars = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (inSingleQuotes) {
                if (c == '\'') {
                    inSingleQuotes = false;
                } else {
                    currentToken.append(c);
                    hasChars = true;
                }
            } else if (inDoubleQuotes) {
                if (c == '"') {
                    inDoubleQuotes = false;
                } else if (c == '\\') {
                    if (i + 1 < input.length()) {
                        char next = input.charAt(i + 1);
                        if (next == '\\' || next == '"' || next == '$' || next == '`') {
                            currentToken.append(next);
                            i++;
                        } else {
                            currentToken.append(c);
                        }
                    } else {
                        currentToken.append(c);
                    }
                    hasChars = true;
                } else {
                    currentToken.append(c);
                    hasChars = true;
                }
            } else {
                if (c == '\'') {
                    inSingleQuotes = true;
                    hasChars = true;
                } else if (c == '"') {
                    inDoubleQuotes = true;
                    hasChars = true;
                } else if (c == '\\') {
                    if (i + 1 < input.length()) {
                        currentToken.append(input.charAt(i + 1));
                        i++;
                    }
                    hasChars = true;
                } else if (Character.isWhitespace(c)) {
                    if (hasChars) {
                        tokens.add(currentToken.toString());
                        currentToken.setLength(0);
                        hasChars = false;
                    }
                } else {
                    currentToken.append(c);
                    hasChars = true;
                }
            }
        }

        if (hasChars) {
            tokens.add(currentToken.toString());
        }

        return tokens;
    }

    private static String getPath(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String[] paths = pathEnv.split(File.pathSeparator);
        for (String p : paths) {
            File file = new File(p, command);
            if (file.exists() && file.isFile() && file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }
}