import java.util.Scanner;
import java.io.File;
import java.io.PrintStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public class Main {
    static String currentDirectory = System.getProperty("user.dir");
    
    static List<String> parseCommand(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            
            if (c == '\\' && !inSingleQuote) {
                // Inside double quotes, backslash only escapes specific characters: \, ", $, `, \n
                if (inDoubleQuote) {
                    if (i + 1 < input.length()) {
                        char next = input.charAt(i + 1);
                        if (next == '\\' || next == '"' || next == '$' || next == '`') {
                            currentToken.append(next);
                            i++; // skip next char
                        } else {
                            currentToken.append(c);
                        }
                    } else {
                        currentToken.append(c);
                    }
                } else {
                    // Outside quotes, backslash always escapes the next character
                    if (i + 1 < input.length()) {
                        currentToken.append(input.charAt(++i));
                    }
                }
            } else if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == ' ' && !inSingleQuote && !inDoubleQuote) {
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
                String pathEnv = System.getenv("PATH");
                String[] directories = pathEnv.split(File.pathSeparator);
                boolean found = false;
                for (String d : directories) {
                    File file = new File(d + File.separator + typeCommand);
                    if (file.exists() && file.canExecute()) {
                        out.println(typeCommand + " is " + file.getAbsolutePath());
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    out.println(typeCommand + ": not found");
                }
            }
        }
    }
    
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.print("$ ");
            System.out.flush();
            
            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine();
            List<String> tokens = parseCommand(input);
            
            if (tokens.isEmpty()) continue;
            
            String command = tokens.get(0);
            
            if (command.equals("exit")) {
                break;
            }
            
            // Check for pipeline
            int pipeIdx = -1;
            for (int i = 1; i < tokens.size(); i++) {
                if (tokens.get(i).equals("|")) {
                    pipeIdx = i;
                    break;
                }
            }
            
            if (pipeIdx != -1) {
                // Handle pipeline
                List<String> cmd1Tokens = new ArrayList<>(tokens.subList(0, pipeIdx));
                List<String> cmd2Tokens = new ArrayList<>(tokens.subList(pipeIdx + 1, tokens.size()));
                
                String cmd1 = cmd1Tokens.get(0);
                String cmd2 = cmd2Tokens.get(0);
                
                if (isBuiltin(cmd1)) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    PrintStream ps = new PrintStream(baos);
                    executeBuiltin(cmd1, cmd1Tokens, ps, System.err);
                    ps.flush();
                    
                    String builtinOutput = baos.toString();
                    
                    if (isBuiltin(cmd2)) {
                        if (cmd2.equals("type")) {
                            cmd2Tokens.add(builtinOutput.trim());
                        }
                        executeBuiltin(cmd2, cmd2Tokens, System.out, System.err);
                    } else {
                        ProcessBuilder pb = new ProcessBuilder(cmd2Tokens);
                        Process process = pb.start();
                        process.getOutputStream().write(builtinOutput.getBytes());
                        process.getOutputStream().close();
                        
                        byte[] buffer = new byte[1024];
                        int read;
                        java.io.InputStream in = process.getInputStream();
                        while ((read = in.read(buffer)) != -1) {
                            System.out.write(buffer, 0, read);
                        }
                        System.out.flush();
                        process.waitFor();
                    }
                } else {
                    ProcessBuilder pb1 = new ProcessBuilder(cmd1Tokens);
                    Process proc1 = pb1.start();
                    
                    if (isBuiltin(cmd2)) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(proc1.getInputStream()));
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (cmd2.equals("type")) {
                                cmd2Tokens.add(line);
                            }
                        }
                        executeBuiltin(cmd2, cmd2Tokens, System.out, System.err);
                        proc1.waitFor();
                    } else {
                        ProcessBuilder pb2 = new ProcessBuilder(cmd2Tokens);
                        Process proc2 = pb2.start();
                        
                        Thread t1 = new Thread(() -> {
                            try {
                                byte[] buffer = new byte[4096];
                                int read;
                                while ((read = proc1.getInputStream().read(buffer)) != -1) {
                                    proc2.getOutputStream().write(buffer, 0, read);
                                    proc2.getOutputStream().flush();
                                }
                            } catch (Exception e) {}
                            finally {
                                try {
                                    proc2.getOutputStream().close();
                                } catch (Exception e) {}
                            }
                        });
                        
                        Thread t2 = new Thread(() -> {
                            try {
                                byte[] buffer = new byte[4096];
                                int read;
                                while ((read = proc2.getInputStream().read(buffer)) != -1) {
                                    System.out.write(buffer, 0, read);
                                }
                                System.out.flush();
                            } catch (Exception e) {}
                        });
                        
                        t1.start();
                        t2.start();
                        
                        proc1.waitFor();
                        proc2.waitFor();
                        t1.join();
                        t2.join();
                    }
                }
            } else {
                // No pipeline: Check for redirection FIRST
                int redirectIdx = -1;
                String redirectOp = null;
                for (int i = 1; i < tokens.size(); i++) {
                    if (tokens.get(i).equals(">") || tokens.get(i).equals(">>") || 
                        tokens.get(i).equals("2>") || tokens.get(i).equals("2>>") ||
                        tokens.get(i).equals("1>") || tokens.get(i).equals("1>>")) {
                        redirectIdx = i;
                        redirectOp = tokens.get(i);
                        break;
                    }
                }
                
                List<String> cmdTokens = tokens;
                File redirectFile = null;
                boolean appendOutput = false;
                boolean appendError = false;
                boolean redirectErrorOnly = false;
                
                if (redirectIdx != -1) {
                    cmdTokens = new ArrayList<>(tokens.subList(0, redirectIdx));
                    if (redirectIdx + 1 < tokens.size()) {
                        String filePath = tokens.get(redirectIdx + 1);
                        if (new File(filePath).isAbsolute()) {
                            redirectFile = new File(filePath);
                        } else {
                            redirectFile = new File(currentDirectory, filePath);
                        }
                        
                        File parentDir = redirectFile.getParentFile();
                        if (parentDir != null) {
                            parentDir.mkdirs();
                        }
                        
                        if (redirectOp.equals(">>") || redirectOp.equals("1>>")) {
                            appendOutput = true;
                        }
                        if (redirectOp.equals("2>") || redirectOp.equals("2>>")) {
                            redirectErrorOnly = true;
                            if (redirectOp.equals("2>>")) appendError = true;
                        }
                    }
                }
                
                if (isBuiltin(command)) {
                    PrintStream outStream = System.out;
                    PrintStream errStream = System.err;
                    PrintStream fileStream = null;
                    
                    if (redirectFile != null) {
                        fileStream = new PrintStream(new FileOutputStream(redirectFile, redirectErrorOnly ? appendError : appendOutput));
                        if (redirectErrorOnly) {
                            errStream = fileStream;
                        } else {
                            outStream = fileStream;
                        }
                    }
                    
                    executeBuiltin(command, cmdTokens, outStream, errStream);
                    
                    if (fileStream != null) {
                        fileStream.close();
                    }
                } else {
                    String pathEnv = System.getenv("PATH");
                    String[] directories = pathEnv.split(File.pathSeparator);
                    boolean found = false;
                    
                    for (String d : directories) {
                        File file = new File(d + File.separator + command);
                        if (file.exists() && file.canExecute()) {
                            found = true;
                            break;
                        }
                    }
                    
                    if (found) {
                        ProcessBuilder pb = new ProcessBuilder(cmdTokens);
                        pb.inheritIO();
                        
                        if (redirectFile != null) {
                            if (redirectErrorOnly) {
                                pb.redirectError(appendError ? ProcessBuilder.Redirect.appendTo(redirectFile) : ProcessBuilder.Redirect.to(redirectFile));
                            } else {
                                pb.redirectOutput(appendOutput ? ProcessBuilder.Redirect.appendTo(redirectFile) : ProcessBuilder.Redirect.to(redirectFile));
                            }
                        }
                        
                        Process process = pb.start();
                        process.waitFor();
                    } else {
                        System.out.println(command + ": command not found");
                    }
                }
            }
        }
    }
}