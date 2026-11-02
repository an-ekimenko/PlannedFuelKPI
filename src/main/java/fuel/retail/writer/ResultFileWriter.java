package fuel.retail.writer;

import fuel.retail.model.KPI;
import fuel.retail.model.PON; 
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Service component responsible for exporting processed KPI transactional data structures 
 * into a flat, semicolon-delimited text format required for downstream ingestion by SAP BW.
 */
public class ResultFileWriter {
    private static final Logger log = LogManager.getLogger(ResultFileWriter.class);
    
    /** Output target text filename for data exchange interface. */
    private static final String OUTPUT_FILENAME = "KPI_v3.txt";
    
    /** Explicit fractional scale factor representing three decimal places. */
    private static final int DECIMAL_SCALE = 3;

    /**
     * Serializes a structured collection of KPI records into the flat integration file.
     * Applies corporate math rounding practices to numerical targets before writing to disk.
     *
     * @param kpiList   the structured collection of metrics extracted from the source spreadsheet
     * @param outputDir target absolute or relative file directory path where the text file will be saved
     * @throws IOException if any unhandled file I/O operations or stream serialization blockages occur
     */
    public void write(List<KPI> kpiList, String outputDir) throws IOException {
        File dir = new File(outputDir);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (created) {
                log.info("Output directory did not exist, successfully created at: {}", dir.getAbsolutePath());
            }
        }

        File txtFile = new File(dir, OUTPUT_FILENAME);
        String currentYear = new SimpleDateFormat("yyyy").format(new Date());

        log.info("Initiating data export sequence. Target path: {}", txtFile.getAbsolutePath());

        // Using standard BufferedWriter bound explicitly to UTF-8 encoding configuration
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(txtFile), StandardCharsets.UTF_8))) {
            int kpiNumInOrder = 1;
            
            for (KPI kpi : kpiList) {
                // Formats numbers as a three-character sequential code padded with leading zeros (e.g., 001, 002)
                String kpiKey = String.format("%03d", kpiNumInOrder);
                
                for (PON pon : kpi.getPons()) {
                    // Record structure format segment 1: Year metadata bounds
                    writer.write(currentYear + ";");
                    writer.write(currentYear + kpiKey + ";");
                    writer.write(kpi.getKpiName() + ";");
                    writer.write(pon.getPonName() + ";");

                    String[] values = pon.getKpiValues();
                    
                    // Record structure format segment 2: 12 monthly transactional data cells
                    for (int i = 0; i < values.length; i++) {
                        // Safe declaration of a default value without the risk of getting an ArithmeticException.
                        BigDecimal roundedResult = new BigDecimal("0.000");
                        
                        if (values[i] != null) {
                            try {
                                // Normalize incoming strings by substituting commas with dot notation for parsing safety
                                String cleanVal = values[i].replace(",", ".").trim();
                                
                                // BigDecimal guarantees exact mathematical rounding constraints without float/double bit precision loss
                                BigDecimal rawValue = new BigDecimal(cleanVal);
                                roundedResult = rawValue.setScale(DECIMAL_SCALE, RoundingMode.CEILING);
                            } catch (NumberFormatException nfe) {
                                log.warn("Malformed numeric token detected: '{}' at KPI: '{}', Enterprise: '{}'. Defaulting cell value to 0.000", 
                                        values[i], kpi.getKpiName(), pon.getPonName());
                            }
                        }
                        
                        writer.write(roundedResult.toPlainString());
                        
                        // Semicolon separator handling for structured records
                        if (i < values.length - 1) {
                            writer.write(";");
                        }
                    }
                    writer.write("\n");
                }
                kpiNumInOrder++;
            }
            writer.flush();
            log.info("Export sequence finalized successfully. Records processed: {}", kpiList.size());
        }
    }
}
