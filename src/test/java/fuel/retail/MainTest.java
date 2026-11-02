package fuel.retail;

import fuel.retail.model.KPI;
import fuel.retail.writer.ResultFileWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MainTest {

    @Test
    @DisplayName("Should break loop immediately if write operations finish successfully on first attempt")
    void shouldPassOnFirstAttemptWithoutRetries(@TempDir File tempOutputDir) throws Exception {
        // Given
        ResultFileWriter mockWriter = mock(ResultFileWriter.class);
        List<KPI> emptyList = new ArrayList<>();
        
        // Reflective execution path access for isolated private orchestration method
        Method retryMethod = Main.class.getDeclaredMethod("executeWriteWithRetry", 
                ResultFileWriter.class, List.class, String.class, long.class, int.class);
        retryMethod.setAccessible(true);
        
        // When & Then
        assertDoesNotThrow(() -> {
            retryMethod.invoke(null, mockWriter, emptyList, tempOutputDir.getAbsolutePath(), 100L, 3);
        });

        // Verify that the persistence layer write was triggered exactly once
        verify(mockWriter, times(1)).write(anyList(), eq(tempOutputDir.getAbsolutePath()));
    }

    @Test
    @DisplayName("Should execute exponential backoff retries and throw IOException when max attempts exhausted")
    void shouldRetryAndThrowExceptionWhenMaxAttemptsExhausted(@TempDir File tempOutputDir) throws Exception {
        // Given
        ResultFileWriter mockWriter = mock(ResultFileWriter.class);
        List<KPI> emptyList = new ArrayList<>();
        
        // Force the mock writer to constantly throw exceptions to simulate a persistent file lock scenario
        doThrow(new IOException("Shared filesystem lock detected")).when(mockWriter).write(anyList(), anyString());

        Method retryMethod = Main.class.getDeclaredMethod("executeWriteWithRetry", 
                ResultFileWriter.class, List.class, String.class, long.class, int.class);
        retryMethod.setAccessible(true);

        // When & Then
        long startTime = System.currentTimeMillis();
        
        try {
            // Run with low baseline timeout (10ms) to ensure high-speed test suite execution boundaries
            retryMethod.invoke(null, mockWriter, emptyList, tempOutputDir.getAbsolutePath(), 10L, 3);
            fail("Orchestrator should have thrown an exception due to exhausted write attempts count limits.");
        } catch (Exception e) {
            // The reflected exception is wrapped inside an InvocationTargetException container
            assertNotNull(e.getCause(), "The root failure context must be propagated.");
            assertTrue(e.getCause() instanceof IOException, "Root cause must be an instance of IOException.");
        }

        long duration = System.currentTimeMillis() - startTime;

        // Verify write attempt counts matches configuration bounds (max 3 tries)
        verify(mockWriter, times(3)).write(anyList(), eq(tempOutputDir.getAbsolutePath()));
        
        // Exponential backoff math validation checks:
        // Attempt 1 fails -> delay = 10 * (1 + 1^0) = 20ms
        // Attempt 2 fails -> delay = 10 * (1 + 2^1) = 30ms
        // Total accumulated sleep time must be at least 50ms
        assertTrue(duration >= 50, "The dynamic backoff scaling delay window was bypassed or calculated incorrectly.");
    }

    @Test
    @DisplayName("Should successfully generate standard structural integration status check receipts file")
    void shouldWriteYamlResultCorrectly() throws Exception {
        // Given
        // Reflectively obtain access to the private writeYamlResult validation target method
        Method writeYamlMethod = Main.class.getDeclaredMethod("writeYamlResult", int.class);
        writeYamlMethod.setAccessible(true);

        // Resolve the destination path relative to the current working environment directory
        Path realBaseDir = Path.of("").toAbsolutePath();
        Path expectedYamlFile = realBaseDir.resolve("exchange").resolve("result.yaml");

        // Backup existing production receipt if it exists to preserve current environment state integrity
        byte[] originalContent = null;
        boolean existedBeforeTest = Files.exists(expectedYamlFile);
        if (existedBeforeTest) {
            originalContent = Files.readAllBytes(expectedYamlFile);
        }

        try {
            // When (Trigger successful status token creation code: 0)
            writeYamlMethod.invoke(null, 0);

            // Then
            assertTrue(Files.exists(expectedYamlFile), "The status token receipt was not written to the disk filesystem directory.");

            List<String> lines = Files.readAllLines(expectedYamlFile, StandardCharsets.UTF_8);
            assertTrue(lines.size() >= 3, "YAML verification block is missing required structural metadata boundaries.");
            assertEquals("output:", lines.get(0), "Root token anchor layout error.");
            assertTrue(lines.get(1).contains("datetime:"), "Universal execution marker tracking timestamp layout error.");
            assertEquals("  returned_code: 0", lines.get(2), "The status check integer code mismatch.");

        } finally {
            // Self-healing environment teardown cleanup phase executed inside a safe finally block
            if (existedBeforeTest && originalContent != null) {
                // Restore the original user environment file to its pre-test footprint state
                Files.write(expectedYamlFile, originalContent);
            } else {
                // Delete temporary test artifacts completely if no pre-existing file was present
                Files.deleteIfExists(expectedYamlFile);
            }
        }
    }
}
