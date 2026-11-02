package fuel.retail.parser;

import fuel.retail.model.KPI;
import fuel.retail.model.PON;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExcelParserTest {

    private ExcelParser excelParser;
    private static final String CURRENT_YEAR = new SimpleDateFormat("yyyy").format(new Date());
    private static final String TARGET_SHEET_NAME = "План_" + CURRENT_YEAR + " ПОН";

    @BeforeEach
    void setUp() {
        excelParser = new ExcelParser();
    }

    @Test
    @DisplayName("Should throw FileNotFoundException when target excel file missing on disk")
    void shouldThrowExceptionWhenFileDoesNotExist() {
        String invalidPath = "completely_invalid_file_path_12345.xls";
        assertThrows(FileNotFoundException.class, () -> excelParser.parse(invalidPath));
    }

    @Test
    @DisplayName("Should correctly extract stateful KPI structures from mocked Apache POI Workbook layers")
    void shouldParseWorkbookStructureCorrectly() throws Exception {
        // Build mocked Apache POI ecosystem
        Workbook mockWorkbook = mock(Workbook.class);
        Sheet mockSheet = mock(Sheet.class);
        CreationHelper mockCreationHelper = mock(CreationHelper.class);
        FormulaEvaluator mockEvaluator = mock(FormulaEvaluator.class);
        CellStyle mockStyle = mock(CellStyle.class);

        when(mockWorkbook.getSheet(TARGET_SHEET_NAME)).thenReturn(mockSheet);
        when(mockWorkbook.getCreationHelper()).thenReturn(mockCreationHelper);
        when(mockCreationHelper.createFormulaEvaluator()).thenReturn(mockEvaluator);
        when(mockStyle.getDataFormatString()).thenReturn("General");

        // Define row limits (0 to 1)
        when(mockSheet.getFirstRowNum()).thenReturn(0);
        when(mockSheet.getLastRowNum()).thenReturn(1);

        // --- Mock Row 0 (Contains KPI definition boundary) ---
        Row mockRow0 = mock(Row.class);
        Cell mockKpiCell0 = mock(Cell.class);
        RichTextString mockRichStr0 = mock(RichTextString.class);
        
        when(mockSheet.getRow(0)).thenReturn(mockRow0);
        when(mockRow0.getCell(1)).thenReturn(mockKpiCell0);
        when(mockKpiCell0.getCellType()).thenReturn(CellType.STRING);
        when(mockKpiCell0.getRichStringCellValue()).thenReturn(mockRichStr0);
        when(mockKpiCell0.getCellStyle()).thenReturn(mockStyle);
        when(mockKpiCell0.getSheet()).thenReturn(mockSheet); 
        when(mockSheet.getWorkbook()).thenReturn(mockWorkbook); 
        when(mockRichStr0.getString()).thenReturn("Темп роста объема розничной реализации нефтепродуктов предприятия");

        // --- Mock Row 1 (Contains Subsidiary PON targets) ---
        Row mockRow1 = mock(Row.class);
        Cell mockKpiCell1 = mock(Cell.class);
        Cell mockPonCell1 = mock(Cell.class);
        RichTextString mockRichStrPon = mock(RichTextString.class);
        
        when(mockSheet.getRow(1)).thenReturn(mockRow1);
        when(mockRow1.getCell(1)).thenReturn(mockKpiCell1); 
        when(mockKpiCell1.getCellType()).thenReturn(CellType.BLANK); 
        
        when(mockRow1.getCell(3)).thenReturn(mockPonCell1);  
        when(mockPonCell1.getCellType()).thenReturn(CellType.STRING);
        when(mockPonCell1.getRichStringCellValue()).thenReturn(mockRichStrPon);
        when(mockPonCell1.getCellStyle()).thenReturn(mockStyle);
        when(mockPonCell1.getSheet()).thenReturn(mockSheet);
        when(mockRichStrPon.getString()).thenReturn("РУП 'Белоруснефть-Брестоблнефтепродукт'");

        // Setup Month data columns E-P (Indices 4-15) inside Row 1
        for (int col = 4; col <= 15; col++) {
            Cell mockValueCell = mock(Cell.class);
            when(mockRow1.getCell(col)).thenReturn(mockValueCell);
            
            // Чтобы DataFormatter не выполнял сложный парсинг числовых масок,
            // переключаем тип на STRING и возвращаем чистое текстовое значение
            when(mockValueCell.getCellType()).thenReturn(CellType.STRING);
            RichTextString mockValueStr = mock(RichTextString.class);
            when(mockValueCell.getRichStringCellValue()).thenReturn(mockValueStr);
            when(mockValueStr.getString()).thenReturn("123.45");
            
            when(mockValueCell.getCellStyle()).thenReturn(mockStyle);
            when(mockValueCell.getSheet()).thenReturn(mockSheet); 
        }

        // Intercept WorkbookFactory initialization using Mockito Static Block
        try (MockedStatic<WorkbookFactory> factoryMock = mockStatic(WorkbookFactory.class)) {
            factoryMock.when(() -> WorkbookFactory.create(any(InputStream.class))).thenReturn(mockWorkbook);

            // Create a small isolated valid local file token to pass exists() constraints
            File tempEmptyFile = File.createTempFile("resilient_test_mock", ".xls");
            tempEmptyFile.deleteOnExit();

            // When
            List<KPI> results = excelParser.parse(tempEmptyFile.getAbsolutePath());

            // Then
            assertNotNull(results);
            assertEquals(1, results.size(), "Stateful Tracking Pattern failed to register new KPI definition block.");
            
            KPI extractedKpi = results.get(0);
            assertTrue(extractedKpi.getKpiName().contains("Темп"));
            assertEquals(1, extractedKpi.getPons().size(), "Subsidiary enterprise PON mapping block missed.");
            
            PON extractedPon = extractedKpi.getPons().get(0);
            assertEquals("РУП 'Белоруснефть-Брестоблнефтепродукт'", extractedPon.getPonName());
            assertEquals("123.45", extractedPon.getKpiValues()[0], "Month numerical data cell parsing constraint broken.");
        }
    }
}
