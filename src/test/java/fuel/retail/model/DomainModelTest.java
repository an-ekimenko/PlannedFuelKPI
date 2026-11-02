package fuel.retail.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DomainModelTest {

    @Test
    @DisplayName("Should trim whitespace when initializing KPI and PON entities")
    void shouldTrimWhitespaceOnInitialization() {
        // Given & When
        KPI kpi = new KPI("   Volume growth rate of fuel sales   ");
        PON pon = new PON("  Enterprise Name  ");

        // Then
        assertEquals("Volume growth rate of fuel sales", kpi.getKpiName());
        assertEquals("Enterprise Name", pon.getPonName());
    }

    @Test
    @DisplayName("Should prevent ArrayOutOfBoundsException and store months correctly within 0-11 range")
    void shouldStoreValuesSafelyWithinMonthBounds() {
        // Given
        PON pon = new PON("Test Enterprise");

        // When
        pon.setKpiValue(0, "100.2");  // January
        pon.setKpiValue(11, "150.5"); // December
        pon.setKpiValue(12, "200.0"); // Out of bounds high
        pon.setKpiValue(-1, "50.0");  // Out of bounds low

        // Then
        assertEquals("100.2", pon.getKpiValues()[0]);
        assertEquals("150.5", pon.getKpiValues()[11]);
        // Rest of the positions remain unitialized (null)
        assertNull(pon.getKpiValues()[1]);
    }
}

