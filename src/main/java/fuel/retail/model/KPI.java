package fuel.retail.model;

import lombok.Getter;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a Key Performance Indicator (KPI) for the fuel retail business.
 * A KPI holds its descriptive name and a collection of Production Units (PONs) 
 * that are measured against this specific metric.
 */
@Getter
public class KPI {
    
    /** The descriptive name of the KPI (e.g., "Volume growth rate of fuel sales..."). */
    private String kpiName;
    
    /** The list of production units (entities) associated with this KPI. */
    private final List<PON> pons = new ArrayList<>();

    /**
     * Constructs a new KPI instance with the specified name.
     *
     * @param kpiName the descriptive name of the indicator
     */
    public KPI(String kpiName) {
        setKpiName(kpiName);
    }

    /**
     * Sets a new name for this KPI, safely eliminating leading and trailing whitespaces.
     *
     * @param kpiName the new KPI name to set
     */
    public void setKpiName(String kpiName) { 
        this.kpiName = kpiName != null ? kpiName.trim() : null; 
    }
}
