package fuel.retail.model;

import lombok.Getter;

/**
 * Represents a Production Unit.
 * Holds the name of the subsidiary enterprise and an array of planned values 
 * corresponding to the 12 months of the year.
 */
public class PON {
    
    /** The name of the enterprise (e.g., "РУП 'Белоруснефть-Брестоблнефтепродукт'"). */
    @Getter
    private String ponName;
    
    /** 
     * Fixed-size array to store targeted KPI numerical values as strings for 12 months.
     * Index 0 corresponds to January, index 11 corresponds to December.
     */
    @Getter
    private final String[] kpiValues = new String[12];

    /**
     * Constructs a new PON instance with the specified enterprise name.
     *
     * @param ponName the name of the subsidiary enterprise
     */
    public PON(String ponName) {
        setPonName(ponName);
    }

    /**
     * Sets a new name for the enterprise, safely eliminating whitespaces.
     *
     * @param ponName the new enterprise name to set
     */
    public void setPonName(String ponName) { 
        this.ponName = ponName != null ? ponName.trim() : null; 
    }
    
    /**
     * Stores a specific target value for a given month index.
     *
     * @param idx the month index (0 for January, 11 for December)
     * @param value the raw text or numerical value extracted from Excel
     */
    public void setKpiValue(int idx, String value) {
        if (idx >= 0 && idx < 12) {
            this.kpiValues[idx] = value;
        }
    }
}

