# PlannedFuelKPI

A high-performance, resilient Java integration utility designed to extract targeted Key Performance Indicators (KPIs) from corporate Excel spreadsheets and convert them into a normalized, flat semicolon-delimited format for downstream ingestion by **SAP BW**.

## Architectural Design & Separation of Concerns

The original script-like codebase suffered from strong architectural coupling (*Tight Coupling*), where initialization, file streaming, cell-parsing logic, mathematical rounding, and output logging were combined inside a single monolith class. 

To bring the application to enterprise-grade compliance, the architecture was fully refactored around the principles of **Separation of Concerns (SoC)** and **Single Responsibility (SRP)**. The system now delegates responsibilities across specialized components, ensuring low coupling and high maintainability:

```text
       [ Infrastructure Layer ]                [ Domain Layer ]                [ Business / ETL Layer ]
 ┌──────────────────────────────────┐        ┌──────────────────┐        ┌──────────────────────────────────┐
 │           Main.java              │        │     KPI.java     │        │        ExcelParser.java          │
 │(Bootstrapping, Retries, Orchestr)│        │  (Domain Model)  │        │(Stateful Stream Extract/Transform)
 └─────────────────┬────────────────┘        └────────▲─────────┘        └────────────────┬─────────────────┘
                   │                                  │                                   │
                   │   Passes Configuration Path      │   Populates Domain Entities       │
                   └──────────────────────────────────┼───────────────────────────────────┘
                   │                                  │
                   │   Dispatches Populated Model     │
                   └──────────────────────────────────┼───────────────────────────────────
                                                      │
                                             ┌────────┴─────────┐
                                             │ ResultFileWriter │
                                             │ (BigDecimal Load)│
                                             └──────────────────┘
```

### Decoupled Component Architecture

*   **`fuel.retail.Main` (The System Orchestrator & Bootstrapper)**  
    Acts purely as the entry point and runtime environment manager. It handles infrastructure bootstrapping, maps filesystem paths dynamically (detecting IDE vs. production JAR modes), reads external configurations, and wraps downstream processes into an automated **Fault-Tolerant Retry Pipeline**. It is completely decoupled from Apache POI libraries and file-writing streams.
*   **`fuel.retail.parser.ExcelParser` (The Extraction & Transformation Layer)**  
    Responsible solely for sheet structure traversal, cell type resolution, and string-to-token parsing. It contains no file destination knowledge, no rounding parameters, and no business delivery logic. It consumes raw binary input streams and transforms them directly into decoupled domain objects.
*   **`fuel.retail.writer.ResultFileWriter` (The Persistence & Formatting Layer)**  
    Responsible exclusively for the output delivery lifecycle. It handles exact data formatting (semicolon layouts), pads metadata keys, and enforces rigid enterprise numeric transformation. It has zero knowledge of spreadsheet structures or configuration profiles.
*   **`fuel.retail.model.KPI` & `fuel.retail.model.PON` (The Domain / Data Model Layer)**  
    Pure data layouts optimized using **Project Lombok** (`@Getter`). They completely isolate data representation away from processing logic, functioning as clean, lightweight message wrappers transferred between the Parser and the Writer components.

---

## Technical Features & Production Resilience

### 1. Dynamic State Tracking for Evolving KPI Structures (Merged Cells)
The structural layout of corporate master planning spreadsheets is highly dynamic; metrics, row counts, and even the sequential order of indicators can change across billing periods or annual reporting templates. The parsing engine completely avoids static indexing or rigid hardcoded string arrays to safeguard against maintenance overhead.

*   **Dynamic Component Extraction:** Rather than initializing a pre-defined fixed matrix of expected KPIs, the processing workflow reads the spreadsheet line-by-line in a streaming fashion.
*   **Stateful Unified Cell Contextualization:** Apache POI native evaluations drop cell value properties for secondary rows bound inside a merged block, mapping physical addresses outside the top-left coordinate origin as `BLANK`. To resolve this safely without data loss or memory-heavy multi-pass caching, `ExcelParser` leverages a **Stateful Tracking Pattern**:
    *   The loop screens each horizontal index layer.
    *   When a text token matching the corporate threshold (`KPI_INDICATOR_KEYWORD` with length > `KPI_MIN_LENGTH_THRESHOLD`) is intercepted, it signals a new structural boundary.
    *   The parser instantiates a fresh `KPI` entity dynamically and appends it to the global runtime collection.
    *   If subsequent rows contain null or blank markers inside the metric column (Column B), the parser preserves the active `KPI` boundary context, seamlessly attaching incoming subsidiary production units (`PON`) to it until the next valid keyword signature occurs. This architecture easily handles dynamically appended rows, altered metrics, or changing enterprise sequences.

### 2. Polyfilla Decimal Point Normalization & Exact Precision Controls
Source spreadsheets are modified manually by multiple departments across regional corporate branches. This leads to common syntactic discrepancies where localized Windows regional configurations shift numbers unpredictably—storing target records with a decimal comma separator (`100,2`) in some cells, and a standard dot notation (`100.2`) in others.

*   **Token Text Cleansing:** The persistence layer (`ResultFileWriter`) intercepts all transactional values as plain text via the `DataFormatter` abstraction. Before dispatching elements to heavy mathematical calculation layers, it normalizes strings by substituting all comma occurrences with uniform dot notation symbols (`cleanVal = values[i].replace(",", ".").trim()`).
*   **Zero-Loss Numeric Serialization:** Standard binary data types (`float` and `double`) are strictly prohibited within the accounting pipeline due to hardware-level binary floating-point representation drifting (e.g., evaluating `93.7` as `93.700000000004`). In analytical platforms like SAP BW, these micro-discrepancies trigger critical ledger imbalances. 
*   **Strict Scale Enforcement:** The system maps clean strings directly into **`BigDecimal`** objects, ensuring exact precision. It explicitly locks values to three fractional digits (`DECIMAL_SCALE = 3`) using a programmatic ceiling boundary rule (`RoundingMode.CEILING`). The output is serialized via `.toPlainString()`, guaranteeing stable, predictably formatted flat files (e.g., `100.100` or `93.700`) completely free of scientific exponential layouts (`1.2E+2`).

### 3. Lock-Free Concurrent Reading via `InputStream`
Corporate spreadsheets hosted on corporate shared network storage instances (`\\aup-fs\Ivc\...`) are frequently locked by accounting or retail planning staff members reviewing live targets. Standard file processing attempts (`WorkbookFactory.create(File)`) fetch lock verification tokens from Windows OS file-handling systems, resulting in a fatal `java.io.FileNotFoundException` (Process cannot access the file because it is being used by another process) during automated night shifts.
*   **Shared Read Execution:** By explicitly extracting data via a buffered `FileInputStream`, the operating system permits overlapping shared read allocations. The integration engine successfully reads targets regardless of active user locks.
*   **In-Memory Lifecycle Isolation:** Once the `InputStream` is passed, Apache POI copies the localized data matrix straight into memory heap contexts near-instantaneously (optimizing `.xls` documents up to 50-100 MB). The utility instantly shuts down the underlying operating system file descriptor, dropping the network target retention footprint to near-zero.

### 4. Exponential Backoff Retry Policy for Network Publication
In enterprise environments, writing output target buffers to distributed file system nodes can collide with active scheduling processes (e.g., a SAP BW extractor pulling data from `KPI_v3.txt` concurrently). To mitigate transient infrastructure collisions, the orchestration engine encapsulates the write subsystem inside an automated loop using an **Exponential Backoff Back-Off Policy**:
*   Transient `IOException` locks trigger a warning state rather than a crash sequence.
*   The execution thread enters a controlled sleep window before recycling attempts, scaling the pause delay progressively using the configured deployment profile parameters:
    \[\text{timeout} = \text{initialTimeoutMs} \times (1 + \text{attempt}^{(\text{attempt} - 1)})\]
*   *Example delays using `initial_timeout_ms=10000` (10 seconds):*
    *   *Failure 1 (Attempt 1):* $10000 \times (1 + 1^0) = 10,000\text{ ms}$ delay before retry.
    *   *Failure 2 (Attempt 2):* $10000 \times (1 + 2^1) = 30,000\text{ ms}$ delay before retry.

---

## Deployment Topology & Artifact Isolation

The Maven layout segregates third-party software artifacts away from application runtimes, isolating core frameworks inside a shared parent directory structure. This setup allows multiple specialized parsing utilities to share a single centralized library pool on the deployment host. The internal subpackages (`config`, `model`, `parser`, `writer`) and automated unit test matrices are cleanly encapsulated inside the parent root package `fuel.retail`:

```text
D:/IT/Java/prj/excel/            # Enterprise Extraction Shared Root Folder
├── dependencies/               # CENTRALIZED SHAREABLE LIBRARY BUNDLE (POI, Log4j2, Lombok, etc.)
│   ├── poi-5.2.5.jar
│   ├── log4j-core-2.23.1.jar
│   └── [Other required third-party libraries...]
└── PlannedFuelKPI/             # Application Execution Profile Home
    ├── config/
    │   ├── config.properties   # Environmental execution parameters
    │   ├── config.properties.dist # Git-tracked profile blueprint configuration
    │   └── log4j2.xml          # Dual-channel (Console & File Appender) descriptor
    ├── exchange/
    │   └── result.yaml         # Process termination receipt descriptor
    ├── logs/
    │   └── application.log     # Active operation logging destination
    ├── src/
    │   ├── main/
    │   │   └── java/
    │   │       └── fuel/
    │   │           └── retail/
    │   │               ├── config/
    │   │               │   └── AppConfig.java
    │   │               ├── model/
    │   │               │   └── KPI.java
    │   │               │   └── PON.java
    │   │               ├── parser/
    │   │               │   └── ExcelParser.java
    │   │               ├── writer/
    │   │               │   └── ResultFileWriter.java
    │   │               └── Main.java
    │   └── test/
    │       └── java/
    │           └── fuel/
    │               └── retail/
    │                   ├── model/
    │                   │   └── DomainModelTest.java
    │                   ├── parser/
    │                   │   └── ExcelParserTest.java
    │                   ├── writer/
    │                   │   └── ResultFileWriterTest.java
    │                   └── MainTest.java
    └── PlannedFuelKPI.jar          # Standalone compiled executable binary module
```

---

## Configuration Reference

### Git Distribution Blueprints (`config/config.properties.dist`)

To protect local credentials and production server path mappings from being accidentally leaked into shared source control branches, `config.properties` is blocked from tracking. Instead, developers maintain an updated blueprint schema file inside the repository:

```properties
# Global configuration template for PlannedFuelKPI service.
# Copy this file to 'config.properties' and replace placeholders with actual deployment environment paths.
excel_file=YOUR_PATH
initial_timeout_ms=10000
max_write_attempts=3
```
### System Receipt Logging (`exchange/result.yaml`)

Upon final lifecycle termination, the orchestration layer guarantees the creation of an analytical status receipt file. Scheduling monitors (such as SAP PI/PO, Control-M, or Apache Airflow) parse this file to evaluate process health states:

```yaml
output:
  datetime: "2026-10-30:16:44:00"  # Exact timestamp of execution completion (YYYY-MM-DD:HH:mm:ss)
  returned_code: 0                 # 0 = Absolute Success / 1 = Fatal Execution Failure
```

#### Visual Structure Breakdown:
*   **`output`**: Root node anchoring the analytical integration metadata block.
*   **`datetime`**: Universal execution marker tracking the exact completion timestamp for pipeline auditing.
*   **`returned_code`**: Rigid integer token used for conditional step branching inside automated job orchestrators:
    *   `0`: Process closed without exceptions; `KPI_v3.txt` is fully verified and ready for SAP BW ingestion.
    *   `1`: Critical infrastructure or I/O bottleneck encountered (e.g., permanent file lock); delivery aborted.


## Testing Framework & Test Scenarios

The utility includes an isolated automated test suite powered by **JUnit 5** and **Mockito** frameworks to guarantee transactional reliability, prevent regression bugs, and safeguard business precision controls. To achieve high-speed execution independent of network states, external directories, or active user locks, the core logic layers are verified in strict isolation using behavioral test doubles and reflection patterns.

### Covered Test Scenarios

#### 1. Integration Orchestration & Fault-Tolerance (`MainTest`)
*   **Isolated Retry Policy Backoff Validation**: Stubs `ResultFileWriter` to continuously emit persistent `IOException` locks. Verifies that the orchestration engine executes exactly up to `max_write_attempts` (3 tries) and mathematically evaluates the Progressive Exponential Backoff sleep window durations before throwing a terminal exception.
*   **Verification Receipt Token Materialization**: Tracks the analytics layer check status inside a safe execution sandbox environment. Asserts that the output `result.yaml` token is generated with a valid metadata layout, dynamic completion timestamps, and the matching success integer code block context (`returned_code: 0`).

#### 2. Stateful Layout Extraction Validation (`ExcelParserTest`)
*   **Merged Blocks Context Maintenance (Stateful Tracking)**: Mimics an incoming row-by-row structural POI sheet block where vertical cells map to `BLANK` properties outside the top-left origin. Asserts that the internal parsing loop preserves the active parent context and dynamically links subsequent enterprise data lines (`PON`) to the target `KPI`.
*   **String Parsing Types Resiliency**: Simulates complex string and decimal inputs associated with corporate worksheets. Asserts that missing values fail gracefully to safe default `"0"` markers instead of crashing with a `NullPointerException`.

#### 3. Data Precision & Scale Controls (`ResultFileWriterTest`)
*   **Token Text Cleansing Strategy**: Asserts that localized decimal punctuation variations (e.g., cell numbers manually input using commas `100,1234`) are successfully normalized to standardized dot notations (`100.1234`) before calculations launch.
*   **Zero-Loss Precision Maintenance**: Validates that raw text numbers are converted into exact `BigDecimal` structures matching strict scale locks (`DECIMAL_SCALE = 3`) and the forced `RoundingMode.CEILING` rule. Ensures output data rows (e.g., `100.124`) are fully verified and predictable.


## Build & Lifecycle Automation

### Compilation Sequence
Execute the automated compilation, dependency gathering, and relocation process by triggering:
```bash
mvn clean package
```

### Internal Plugin Sequence Execution Details
1. **`maven-dependency-plugin`:** Extracts required binary library assets from local repos and exports them to the centralized shared cache folder located one step above the active root (`../dependencies`).
2. **`maven-jar-plugin`:** Generates the application archive. It automatically configures the internal manifest metadata file, injecting a cross-platform, forward-slash-delimited `Class-Path` header pointing exactly to the shared cache (`../dependencies/`). It also registers `fuel.retail.Main` as the system execution entry point.
3. **`maven-resources-plugin`:** Copies the compiled `PlannedFuelKPI.jar` out of the temporary local `/target` workspace folder directly back into the project base directory (`${project.basedir}`).

### Running the Production Bundle
Launch the autonomous engine using standard virtual machine initialization commands, providing the absolute or relative path to the application directory:

```bash
java -jar /path/to/PlannedFuelKPI.jar
```
