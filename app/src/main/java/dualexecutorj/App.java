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

public class App {
    private static Process backgroundProcess;
    private static Process foregroundProcess;

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
        if (foregroundProcess != null) {
            foregroundProcess.destroy();
        }
        if (backgroundProcess != null) {
            try {
                if (backgroundProcess.isAlive()) {
                    try (OutputStream backgroundOutputStream = backgroundProcess.getOutputStream()) {
                        backgroundOutputStream.write("stop\n".getBytes());
                        backgroundOutputStream.flush();
                    }
                    backgroundProcess.waitFor(5, TimeUnit.SECONDS); // Wait with timeout
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

        // Background process output reader thread
        Thread backgroundThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(backgroundProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("Background: " + line);
                }
            } catch (IOException e) {
                if (backgroundProcess.isAlive()) {
                    System.err.println("Background process input stream closed unexpectedly.");
                }
            }
        });
        backgroundThread.setDaemon(true);
        backgroundThread.start();

        // Console input to foreground process thread
        Thread consoleThread = new Thread(() -> {
            try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));
                 PrintWriter foregroundWriter = new PrintWriter(foregroundProcess.getOutputStream(), true)) {
                String line;
                while ((line = consoleReader.readLine()) != null) {
                    foregroundWriter.println(line);
                }
            } catch (IOException e) {
                System.err.println("Error handling console input: " + e.getMessage());
            }
        });
        consoleThread.setDaemon(true);
        consoleThread.start();

        // Foreground process output reader
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(foregroundProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }

        foregroundProcess.waitFor();
        cleanupProcesses();
    }
}
