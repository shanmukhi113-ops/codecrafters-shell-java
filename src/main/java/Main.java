import java.util.Scanner;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Main {
    static String currentDirectory = System.getProperty("user.dir");
    
    static List<String> parseCommand(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder currentToken = new StringBuilder();
        boolean inSingleQuote = false;
        
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            
            if (c == '\'' && !inSingleQuote) {
                inSingleQuote = true;
            } else if (c == '\'' && inSingleQuote) {
                inSingleQuote = false;
            } else if (c == ' ' && !inSingleQuote) {
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
    
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.print("$ ");
            System.out.flush();
            
            String input = scanner.nextLine();
            List<String> tokens = parseCommand(input);
            
            if (tokens.isEmpty()) continue;
            
            String command = tokens.get(0);
            
            if (command.equals("exit")) {
                break;
            } else if (command.equals("echo")) {
                StringBuilder output = new StringBuilder();
                for (int i = 1; i < tokens.size(); i++) {
                    if (i > 1) output.append(" ");
                    output.append(tokens.get(i));
                }
                System.out.println(output.toString());
            } else if (command.equals("pwd")) {
                System.out.println(currentDirectory);
            } else if (command.equals("cd")) {
                if (tokens.size() < 2) continue;
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
                    currentDirectory = dir.getCanonicalPath();
                } else {
                    System.out.println("cd: " + originalPath + ": No such file or directory");
                }
            } else if (command.equals("type")) {
                if (tokens.size() < 2) continue;
                String typeCommand = tokens.get(1);
                if (typeCommand.equals("echo") || typeCommand.equals("exit") || typeCommand.equals("type") || typeCommand.equals("pwd") || typeCommand.equals("cd")) {
                    System.out.println(typeCommand + " is a shell builtin");
                } else {
                    String pathEnv = System.getenv("PATH");
                    String[] directories = pathEnv.split(File.pathSeparator);
                    boolean found = false;
                    
                    for (String dir : directories) {
                        File file = new File(dir + File.separator + typeCommand);
                        if (file.exists() && file.canExecute()) {
                            System.out.println(typeCommand + " is " + file.getAbsolutePath());
                            found = true;
                            break;
                        }
                    }
                    
                    if (!found) {
                        System.out.println(typeCommand + ": not found");
                    }
                }
            } else {
                String pathEnv = System.getenv("PATH");
                String[] directories = pathEnv.split(File.pathSeparator);
                boolean found = false;
                
                for (String dir : directories) {
                    File file = new File(dir + File.separator + command);
                    if (file.exists() && file.canExecute()) {
                        found = true;
                        break;
                    }
                }
                
                if (found) {
                    ProcessBuilder pb = new ProcessBuilder(tokens);
                    pb.inheritIO();
                    Process process = pb.start();
                    process.waitFor();
                } else {
                    System.out.println(command + ": command not found");
                }
            }
        }
    }
}