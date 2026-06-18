import java.util.Scanner;
import java.io.File;

public class Main {
    static String currentDirectory = System.getProperty("user.dir");
    
    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            System.out.print("$ ");
            System.out.flush();
            
            String input = scanner.nextLine();
            String[] parts = input.split(" ", 2);
            String command = parts[0];
            String rest = (parts.length > 1) ? parts[1] : "";
            
            if (command.equals("exit")) {
                break;
            } else if (command.equals("echo")) {
                System.out.println(rest);
            } else if (command.equals("pwd")) {
                System.out.println(currentDirectory);
            } else if (command.equals("cd")) {
                String path = rest.trim();
                File dir;
                
                if (new File(path).isAbsolute()) {
                    dir = new File(path);
                } else {
                    dir = new File(currentDirectory, path);
                }
                
                if (dir.exists() && dir.isDirectory()) {
                    currentDirectory = dir.getCanonicalPath();
                } else {
                    System.out.println("cd: " + path + ": No such file or directory");
                }
            } else if (command.equals("type")) {
                String[] typeParts = rest.trim().split(" +");
                String typeCommand = typeParts[0];
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
                    StringBuilder shellCmd = new StringBuilder(command);
                    if (!rest.isEmpty()) {
                        shellCmd.append(" ").append(rest);
                    }
                    
                    ProcessBuilder pb = new ProcessBuilder("sh", "-c", shellCmd.toString());
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