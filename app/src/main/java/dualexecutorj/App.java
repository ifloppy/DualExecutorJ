package dualexecutorj;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Properties;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;
import java.io.BufferedInputStream;

public class App {

    private static Process backgroundProcess;
    private static Process foregroundProcess;
    private static Thread backgroundThread;
    private static Thread consoleThread;

    private static final int BUFFER_SIZE = 8192; // 8KB buffer

    private static void releaseConfig() throws IOException {
        File configFile = new File("./config.properties");
        if (!configFile.exists()) {
            try (InputStream in = App.class.getClassLoader().getResourceAsStream("config.properties")) {
                if (in != null) {
                    try (FileOutputStream out = new FileOutputStream(configFile)) {
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = in.read(buffer)) > 0) {
                            out.write(buffer, 0, length);
                        }
                    }
                }
            }
        }
    }

    private static void cleanupProcesses() {
        // Close threads first
        if (consoleThread != null) {
            consoleThread.interrupt();
        }
        if (backgroundThread != null) {
            backgroundThread.interrupt();
        }

        // Then clean up processes
        if (foregroundProcess != null) {
            foregroundProcess.destroyForcibly(); // More reliable than destroy()
            try {
                foregroundProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // Ignore interruption during shutdown
            }
        }
        if (backgroundProcess != null) {
            try {
                if (backgroundProcess.isAlive()) {
                    try (OutputStream backgroundOutputStream = backgroundProcess.getOutputStream()) {
                        backgroundOutputStream.write("stop\n".getBytes());
                        backgroundOutputStream.flush();
                    }
                    if (!backgroundProcess.waitFor(5, TimeUnit.SECONDS)) {
                        backgroundProcess.destroyForcibly();
                    }
                }
            } catch (Exception e) {
                backgroundProcess.destroyForcibly();
            }
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        // Register shutdown hook for clean process termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            cleanupProcesses();
        }));

        Properties props = new Properties();
        releaseConfig();
        
        try (FileInputStream configInput = new FileInputStream("./config.properties")) {
            props.load(configInput);
        } catch (IOException e) {
            props.setProperty("backgroundCommand", "config_undefined");
            props.setProperty("foregroundCommand", "config_undefined");
            props.setProperty("backgroundWorkDir", ".");
            props.setProperty("foregroundWorkDir", ".");
        }

        String backgroundCommand = props.getProperty("backgroundCommand");
        String foregroundCommand = props.getProperty("foregroundCommand");
        String backgroundWorkDir = props.getProperty("backgroundWorkDir", ".");
        String foregroundWorkDir = props.getProperty("foregroundWorkDir", ".");

        ProcessBuilder backgroundProcessBuilder = new ProcessBuilder(Arrays.asList(backgroundCommand.split(" ")));
        backgroundProcessBuilder.directory(new File(backgroundWorkDir));
        backgroundProcess = backgroundProcessBuilder.start();

        ProcessBuilder foregroundProcessBuilder = new ProcessBuilder(Arrays.asList(foregroundCommand.split(" ")));
        foregroundProcessBuilder.directory(new File(foregroundWorkDir));
        foregroundProcess = foregroundProcessBuilder.start();

        // Store thread references
        backgroundThread = new Thread(() -> {
            try (BufferedInputStream bis = new BufferedInputStream(backgroundProcess.getInputStream(), BUFFER_SIZE);
                 BufferedInputStream bisErr = new BufferedInputStream(backgroundProcess.getErrorStream(), BUFFER_SIZE);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(bis), BUFFER_SIZE);
                 BufferedReader errorReader = new BufferedReader(new InputStreamReader(bisErr), BUFFER_SIZE)) {
                
                String line;
                while (!Thread.interrupted() && (line = reader.readLine()) != null) {
                    System.out.println("Background: " + line);
                    Thread.sleep(1); // Small delay to prevent CPU hogging
                }
                while (!Thread.interrupted() && (line = errorReader.readLine()) != null) {
                    System.err.println("Background Error: " + line);
                    Thread.sleep(1); // Small delay to prevent CPU hogging
                }
            } catch (IOException e) {
                if (backgroundProcess.isAlive() && !Thread.interrupted()) {
                    System.err.println("Background process input stream closed unexpectedly.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        backgroundThread.setDaemon(true);
        backgroundThread.start();

        // Store thread reference and handle error stream
        consoleThread = new Thread(() -> {
            try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));
                 PrintWriter foregroundWriter = new PrintWriter(foregroundProcess.getOutputStream(), true)) {
                String line;
                while (!Thread.interrupted() && (line = consoleReader.readLine()) != null) {
                    foregroundWriter.println(line);
                }
            } catch (IOException e) {
                if (!Thread.interrupted()) {
                    System.err.println("Error handling console input: " + e.getMessage());
                }
            }
        });
        consoleThread.setDaemon(true);
        consoleThread.start();

        // Handle both output and error streams for foreground process
        try (BufferedInputStream bis = new BufferedInputStream(foregroundProcess.getInputStream(), BUFFER_SIZE);
             BufferedInputStream bisErr = new BufferedInputStream(foregroundProcess.getErrorStream(), BUFFER_SIZE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(bis), BUFFER_SIZE);
             BufferedReader errorReader = new BufferedReader(new InputStreamReader(bisErr), BUFFER_SIZE)) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                Thread.sleep(1); // Small delay to prevent CPU hogging
            }
            while ((line = errorReader.readLine()) != null) {
                System.err.println("Foreground Error: " + line);
                Thread.sleep(1); // Small delay to prevent CPU hogging
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        foregroundProcess.waitFor();
        cleanupProcesses();
    }
}
