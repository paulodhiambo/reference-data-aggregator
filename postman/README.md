# Reference Data Aggregator — Postman Collection Guide

This directory contains the automated Postman/Newman test suite for validating the Reference Data Aggregator service endpoints, JSON response envelopes, ETag-based caching, and error handling behaviors.

## 📂 Files

* **Collection JSON:** [reference_data_aggregator.postman_collection.json](reference_data_aggregator.postman_collection.json) — Contains the pre-configured requests, environment variables, validation scripts, and automated assertions.

## 🚀 How to Run the Tests

### Option 1: Import into Postman App

1. Open the Postman Desktop application.
2. Click the **Import** button in the top left workspace panel.
3. Drag and drop the [reference_data_aggregator.postman_collection.json](reference_data_aggregator.postman_collection.json) file or browse to select it.
4. Once imported, you will see a collection named **Reference Data Aggregator**.
5. Set up the collection-level variables (e.g., `baseUrl` defaulting to `http://localhost:8080`) if running against a custom environment.
6. Click on the collection name and select **Run Collection** to execute all tests sequentially.

### Option 2: Execute via Newman (CLI Runner)

Newman is the command-line collection runner for Postman. It allows you to run and test your collection directly from your terminal or CI/CD pipeline.

1. **Install Newman** globally via npm:
   ```bash
   npm install -g newman
   ```
2. **Run the collection** against your running local server:
   ```bash
   newman run postman/reference_data_aggregator.postman_collection.json --envVar baseUrl=http://localhost:8080
   ```

## 🧪 Validated Scenarios

The collection verifies:
- **Search & Pagination:** Filtering countries by name, continent, currency, language, and sorting with offset pagination.
- **Conditional GETs (ETags):** Validates that subsequent requests return `304 Not Modified` with matching ETag headers, and that requesting a stale flag returns fresh states.
- **Envelope Schemas:** Verifies JSON response structure, including the presence of `dataAsOf`, `stale`, and standard pagination metadata.
- **RFC 9457 Problem Details:** Asserts validation errors return `400 Bad Request` with appropriate correlation IDs, and missing endpoints return standard `404 Not Found` responses.
