package fuel.retail;

import fuel.retail.model.KPI;
import fuel.retail.parser.ExcelParser;
import fuel.retail.writer.ResultFileWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Main application orchestrator for the PlannedFuelKPI integration service.
 * Coordinates system configuration bootstrapping, handles robust retry policies for file I/O operations,
 * and registers execution statuses for tracking systems.
 */
public class Main {
    
    private static final Logger log;
    private static final Path baseDir;

    static {
        Path resolvedPath;
        try {
            File runningFile = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (runningFile.isFile()) {
                resolvedPath = runningFile.getParentFile().toPath();
            } else {
                resolvedPath = Path.of("").toAbsolutePath();
            }
        } catch (URISyntaxException e) {
            resolvedPath = Path.of("").toAbsolutePath();
        }
        
        baseDir = resolvedPath;
        
        System.setProperty("log4j.configurationFile", baseDir.resolve("config").resolve("log4j2.xml").toString());
        log = LogManager.getLogger(Main.class);
    }
    
    public static void main(String[] args) {
        int returnCode = 0;
        Properties properties = new Properties();
        Path configPath = baseDir.resolve("config").resolve("config.properties");

        try {
            log.info("Bootstrapping PlannedFuelKPI service workflow. Base active directory: {}", baseDir);
            
            if (!Files.exists(configPath)) {
                throw new FileNotFoundException("Required system configuration missing at: " + configPath.toAbsolutePath());
            }

            try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }

            String excelFile = properties.getProperty("excel_file");
            if (excelFile == null || excelFile.trim().isEmpty()) {
                throw new IllegalArgumentException("The property 'excel_file' is missing inside config.properties.");
            }
            
            // Extract and parse dynamic retry policy parameters from application profile
            String timeoutProp = properties.getProperty("initial_timeout_ms", "1000");
            long initialTimeoutMs = Long.parseLong(timeoutProp.trim());
            
            String attemptsProp = properties.getProperty("max_write_attempts", "3");
            int maxWriteAttempts = Integer.parseInt(attemptsProp.trim());
            
            log.info("Target master spreadsheet registered: {}", excelFile);

            int lastIndex = excelFile.replace('\\', '/').lastIndexOf('/');
            String outputDir = (lastIndex != -1) ? excelFile.substring(0, lastIndex) : baseDir.toString();

            // Step 1: Extract and transform spreadsheet rows (using shared stream isolation against locking)
            ExcelParser parser = new ExcelParser();
            List<KPI> kpiList = parser.parse(excelFile);
            
            // Step 2: Load and execute stateful file destination publisher with embedded dynamic Retry Policy
            ResultFileWriter fileWriter = new ResultFileWriter();
            executeWriteWithRetry(fileWriter, kpiList, outputDir, initialTimeoutMs, maxWriteAttempts);
            
            log.info("Data workflow pipeline finalized with success. Target directory: {}", outputDir);

        } catch (Exception e) {
            log.error("Fatal exception intercepted during application execution", e);
            returnCode = 1;
        } finally {
            writeYamlResult(returnCode);
            log.info("Service execution pipeline closed with return code: {}", returnCode);
            System.exit(returnCode);
        }
    }

    /**
     * Executes file persistence using an exponential backoff retry mechanism to mitigate concurrent I/O locking.
     * Formula applied for backoff calculations: timeout = initialTimeoutMs * (1 + attempt^(attempt - 1))
     */
    private static void executeWriteWithRetry(ResultFileWriter fileWriter, List<KPI> kpiList, String outputDir, 
                                             long initialTimeoutMs, int maxWriteAttempts) throws IOException {
        int attempt = 1;
        
        while (true) {
            try {
                log.info("Attempting file output export sequence (Attempt {}/{})...", attempt, maxWriteAttempts);
                fileWriter.write(kpiList, outputDir);
                break; // Breakthrough immediately if write operation passes without triggering exceptions
            } catch (IOException ioException) {
                log.warn("File write sequence failed on attempt {} due to I/O or locking constraint: {}", attempt, ioException.getMessage());
                
                if (attempt >= maxWriteAttempts) {
                    log.error("Maximum retry operational bounds reached. Out of file publication attempts.");
                    throw ioException; // Rethrow to propagate failure back into termination chains
                }
                
                // Mathematical evaluation: initialTimeoutMs * (1 + attempt^(attempt - 1))
                long sleepDuration = (long) (initialTimeoutMs * (1 + Math.pow(attempt, attempt - 1)));
                log.info("Backoff policy engaged. Thread entering sleep state for {} ms before scheduling retry...", sleepDuration);
                
                try {
                    Thread.sleep(sleepDuration);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Retry operational state interrupted unexpectedly during sleep sequence", ie);
                }
                
                attempt++;
            }
        }
    }

    private static void writeYamlResult(int code) {
        Path exchangeDir = baseDir.resolve("exchange");
        try {
            if (!Files.exists(exchangeDir)) {
                Files.createDirectories(exchangeDir);
            }
            Path yamlFile = exchangeDir.resolve("result.yaml");
            String timestamp = new SimpleDateFormat("yyyy-MM-dd:HH:mm:ss").format(new Date());

            try (BufferedWriter writer = Files.newBufferedWriter(yamlFile, StandardCharsets.UTF_8)) {
                writer.write("output:\n");
                writer.write("  datetime: " + timestamp + "\n");
                writer.write("  returned_code: " + code + "\n");
            }
        } catch (IOException e) {
            System.err.println("Critical error writing final orchestration metadata 'result.yaml': " + e.getMessage());
        }
    }
}
