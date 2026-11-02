package fuel.retail.parser;

import fuel.retail.model.KPI;
import fuel.retail.model.PON; 
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Service class responsible for parsing targeted KPI operational metrics from Excel spreadsheets.
 * Handles structural challenges like merged cells by maintaining contextual processing state
 * during a row-by-row streaming sequence.
 */
public class ExcelParser {
    private static final Logger log = LogManager.getLogger(ExcelParser.class);

    /** Expected prefix within a cell to identify a valid KPI section block. */
    private static final String KPI_INDICATOR_KEYWORD = "Темп";
    
    /** Minimum length threshold for a KPI description to filter out short miscellaneous labels. */
    private static final int KPI_MIN_LENGTH_THRESHOLD = 30;

    /**
     * Parses the specified Excel file and extracts KPI metrics along with subsidiary enterprise targets.
     * Automatically maps data dynamically based on the current calendar year sheet name.
     *
     * @param excelFilePath absolute or relative path to the target source Excel file (.xls or .xlsx)
     * @return a structured list of fully populated KPI domain models
     * @throws Exception if the file is missing, the targeted sheet is absent, or processing fails
     */
    public List<KPI> parse(String excelFilePath) throws Exception {
        List<KPI> kpiList = new ArrayList<>();
        File file = new File(excelFilePath);
        
        if (!file.exists()) {
            throw new FileNotFoundException("Target spreadsheet file not found at: " + excelFilePath);
        }

        // It allows the operating system to share the read stream, ignoring the Excel editing lock.
        try (InputStream fileStream = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(fileStream)) {
             
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            
            // Dynamic sheet selection "План_[ТекущийГод] ПОН"
            String sheetName = "План_" + new SimpleDateFormat("yyyy").format(new Date()) + " ПОН";
            Sheet sheet = workbook.getSheet(sheetName);
            
            if (sheet == null) {
                throw new IllegalArgumentException("Target sheet '" + sheetName + "' could not be found in the workbook.");
            }

            KPI currentKpi = null;
            DataFormatter formatter = new DataFormatter();

            int rowStart = sheet.getFirstRowNum();
            int rowEnd = sheet.getLastRowNum();

            for (int i = rowStart; i <= rowEnd; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                // Column B (Index 1) — Name of the indicator
                Cell kpiCell = row.getCell(1);
                String kpiNameValue = getCellValueAsString(kpiCell, evaluator, formatter);

                if (kpiNameValue != null && kpiNameValue.trim().length() > KPI_MIN_LENGTH_THRESHOLD 
                        && kpiNameValue.contains(KPI_INDICATOR_KEYWORD)) {
                    currentKpi = new KPI(kpiNameValue.trim());
                    kpiList.add(currentKpi);
                }

                if (currentKpi == null) continue;

                // Column D (Index 3) — Company Name (PON)
                Cell ponCell = row.getCell(3);
                String ponName = getCellValueAsString(ponCell, evaluator, formatter);

                if (ponName != null && (ponName.contains("нефт") || ponName.contains("ОАО"))) {

                    PON pon = new PON(ponName.trim());
                    currentKpi.getPons().add(pon);

                    // Columns E-P (Indices 4-15) — 12 months
                    int monthIdx = 0;
                    for (int col = 4; col <= 15; col++) {
                        Cell valueCell = row.getCell(col);
                        String valStr = getCellValueAsString(valueCell, evaluator, formatter);
                        
                        pon.setKpiValue(monthIdx++, (valStr == null || valStr.trim().isEmpty()) ? "0" : valStr.trim());
                    }
                }
            }
        } 
        return kpiList;
    }


    /**
     * Helper abstraction layer to parse and resolve cell data types into a consistent string layout.
     * Evaluates calculations dynamically if a cell contains embedded spreadsheet formulas.
     *
     * @param cell the cell target to read from
     * @param evaluator the workbook's native helper utility to calculate formulas
     * @param formatter utility to preserve explicit Excel localized text formatting
     * @return the textual contents of the cell, or null if empty
     */
    private String getCellValueAsString(Cell cell, FormulaEvaluator evaluator, DataFormatter formatter) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        
        if (cell.getCellType() == CellType.FORMULA) {
            try {
                /* To preserve the number formatting (without .0 at the end of integers),  - 
                 * use formatter.formatCellValue on top of the calculated value.
                 */
                return formatter.formatCellValue(cell, evaluator);
            } catch (Exception e) {
                log.warn("Failed to evaluate internal formula at spreadsheet address: {}", cell.getAddress(), e);
                return null;
            }
        }
        return formatter.formatCellValue(cell);
    }
}
