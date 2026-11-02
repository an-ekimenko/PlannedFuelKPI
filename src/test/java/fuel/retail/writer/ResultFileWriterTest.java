package fuel.retail.writer;

import fuel.retail.model.KPI;
import fuel.retail.model.PON;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResultFileWriterTest {

    @Test
    @DisplayName("Should successfully serialize structural KPI list into exact formatted flat CSV format using BigDecimal controls")
    void shouldSerializeKpiRecordsToFlatFile(@TempDir File tempOutputFolder) throws IOException {
        // Given
        ResultFileWriter fileWriter = new ResultFileWriter();
        String currentYear = new SimpleDateFormat("yyyy").format(new Date());

        // Prepare transactional domain dataset mock layers
        List<KPI> kpiList = new ArrayList<>();
        KPI kpi1 = new KPI("Темп роста розничного товарооборота");
        PON pon1 = new PON("РУП БН-Брест");
        
        // Mock 12 months with various challenging formats (spaces, commas, trailing decimals)
        pon1.setKpiValue(0, " 100,1234 "); // Should normalize comma and round ceiling to 100.124
        pon1.setKpiValue(1, "95.500");      // Should stay precise as 95.500
        pon1.setKpiValue(2, "invalid_num");  // Should default safely to 0.000 via try-catch
        for (int i = 3; i < 12; i++) {
            pon1.setKpiValue(i, "0");
        }
        kpi1.getPons().add(pon1);
        kpiList.add(kpi1);

        // When
        fileWriter.write(kpiList, tempOutputFolder.getAbsolutePath());

        // Then
        File expectedOutputFile = new File(tempOutputFolder, "KPI_v3.txt");
        assertTrue(expectedOutputFile.exists(), "The exporter component failed to materialize the target file on disk.");

        // Extract written line elements using native NIO character strings validation controls
        List<String> outputLines = Files.readAllLines(expectedOutputFile.toPath(), StandardCharsets.UTF_8);
        assertEquals(1, outputLines.size(), "Total records mismatch inside final integration output asset.");

        String actualRecord = outputLines.get(0);
        
        // Verify key metadata formats, semicolon segment splits, and BigDecimal ceiling scale configurations
        assertTrue(actualRecord.startsWith(currentYear + ";"), "Year bounds layout missing.");
        assertTrue(actualRecord.contains(currentYear + "001;"), "Padded KPI sequential indexing code code layout failed.");
        assertTrue(actualRecord.contains("Темп роста розничного товарооборота;"), "KPI Name serialization layout error.");
        assertTrue(actualRecord.contains("РУП БН-Брест;"), "Enterprise PON layout error.");
        
        // Target precision validation segments evaluation
        assertTrue(actualRecord.contains("100.124;"), "Commas replacement or RoundingMode.CEILING precision constraint broke calculation parameters.");
        assertTrue(actualRecord.contains("95.500;"), "Exact trailing zero numeric precision retention failed.");
        assertTrue(actualRecord.contains("0.000;"), "NumberFormatException fallback handling strategy failed to protect cell defaults configuration.");
    }
}
