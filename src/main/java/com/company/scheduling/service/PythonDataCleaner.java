package com.company.scheduling.service;

import com.company.scheduling.dto.DataQualityReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Python数据清洗桥接服务
 * 通过stdin/stdout JSON与 scripts/data_cleaner.py 通信，
 * Python不可用或异常时降级到 {@link JavaDataCleaner}
 */
@Service
public class PythonDataCleaner {

    private static final Logger log = LoggerFactory.getLogger(PythonDataCleaner.class);

    @Value("${python.executable.path:python}")
    private String pythonPath;

    @Value("${python.script.path:scripts/data_cleaner.py}")
    private String scriptPath;

    @Autowired
    private JavaDataCleaner javaDataCleaner; // 降级方案

    /**
     * 清洗织造数据
     *
     * @param rows Excel原始行数据 List<Map<String,String>>
     * @return DataQualityReport 清洗结果
     */
    public DataQualityReport cleanWeavingData(List<Map<String, String>> rows) {
        return invokePython(rows, "weaving", null);
    }

    /**
     * 清洗共挤数据
     *
     * @param sourceYear 从文件名提取的年份
     */
    public DataQualityReport cleanCoexData(List<Map<String, String>> rows, Integer sourceYear) {
        return invokePython(rows, "coex", sourceYear);
    }

    /**
     * 清洗库存数据
     */
    public DataQualityReport cleanInventoryData(List<Map<String, String>> rows) {
        return invokePython(rows, "inventory", null);
    }

    private DataQualityReport invokePython(List<Map<String, String>> rows, String dataType, Integer sourceYear) {
        try {
            // 1. 构建输入JSON
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> input = new HashMap<>();
            input.put("data_type", dataType);
            if (sourceYear != null) {
                input.put("source_year", sourceYear);
            }
            input.put("rows", rows);
            String inputJson = mapper.writeValueAsString(input);

            // 2. 调用Python脚本
            ProcessBuilder pb = new ProcessBuilder(pythonPath, scriptPath);
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // 写入stdin
            try (OutputStream os = process.getOutputStream()) {
                os.write(inputJson.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            // 在独立线程中读取stdout，避免读取阻塞导致超时机制失效
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> outputFuture = executor.submit(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            });

            // 同时消费stderr，避免管道写满导致子进程死锁
            Future<String> errorFuture = executor.submit(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    return reader.lines().collect(Collectors.joining("\n"));
                }
            });

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                executor.shutdownNow();
                log.warn("Python清洗超时，降级到Java内置清洗");
                return javaDataCleaner.clean(rows, dataType, sourceYear);
            }

            String output = outputFuture.get(5, TimeUnit.SECONDS);
            String errorOutput = errorFuture.get(5, TimeUnit.SECONDS);
            executor.shutdown();

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("Python清洗异常 exit={}: {}, 降级到Java内置清洗", exitCode, errorOutput);
                return javaDataCleaner.clean(rows, dataType, sourceYear);
            }

            // 3. 解析输出JSON
            return parseOutput(output);

        } catch (Exception e) {
            log.error("Python调用失败，降级到Java内置清洗", e);
            return javaDataCleaner.clean(rows, dataType, sourceYear);
        }
    }

    private DataQualityReport parseOutput(String json) {
        // 解析Python输出的JSON为DataQualityReport
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode node = mapper.readTree(json);
            if (node.has("error")) {
                throw new RuntimeException("Python清洗错误: " + node.get("error").asText());
            }
            DataQualityReport report = new DataQualityReport();
            report.setTotalRows(node.path("total_rows").asInt());
            report.setGradeACount(node.path("grade_a_count").asInt());
            report.setGradeBCount(node.path("grade_b_count").asInt());
            report.setGradeCCount(node.path("grade_c_count").asInt());

            // 解析grade_b_details
            List<String> bDetails = new ArrayList<>();
            JsonNode bNode = node.path("grade_b_details");
            if (bNode.isArray()) {
                for (JsonNode item : bNode) {
                    bDetails.add(item.asText());
                }
            }
            report.setGradeBDetails(bDetails);

            // 解析grade_c_reasons
            List<String> cReasons = new ArrayList<>();
            JsonNode cNode = node.path("grade_c_reasons");
            if (cNode.isArray()) {
                for (JsonNode item : cNode) {
                    cReasons.add(item.asText());
                }
            }
            report.setGradeCReasons(cReasons);

            // 解析cleaned_data
            List<Map<String, Object>> cleanedData = new ArrayList<>();
            JsonNode dataNode = node.path("cleaned_data");
            if (dataNode.isArray()) {
                for (JsonNode item : dataNode) {
                    Map<String, Object> record = new HashMap<>();
                    Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        record.put(field.getKey(), jsonNodeToObject(field.getValue()));
                    }
                    cleanedData.add(record);
                }
            }
            report.setCleanedData(cleanedData);

            return report;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析Python输出失败", e);
            throw new RuntimeException("数据清洗结果解析失败", e);
        }
    }

    /**
     * 将JsonNode转换为Java对象（数字/字符串/布尔/null）
     */
    private Object jsonNodeToObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.asText();
    }
}
