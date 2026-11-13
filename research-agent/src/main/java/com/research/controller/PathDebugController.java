package com.research.controller;

import com.research.config.AgentProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/debug")
public class PathDebugController {

    private final AgentProperties props;

    public PathDebugController(AgentProperties props) {
        this.props = props;
    }

    @GetMapping("/path")
    public ResponseEntity<?> path() {
        Map<String, Object> r = new LinkedHashMap<>();
        Path dir = Path.of(props.getReport().getOutputDir());
        r.put("outputDir", dir.toString());
        r.put("codepoints", dir.toString().codePoints()
                .mapToObj(cp -> String.format("U+%04X", cp))
                .reduce((a, b) -> a + " " + b).orElse(""));
        r.put("fileEncoding", System.getProperty("file.encoding"));
        r.put("jnuEncoding", System.getProperty("sun.jnu.encoding"));
        r.put("exists", Files.exists(dir));
        try {
            Path test = dir.resolve("debug-write-test.txt");
            Files.writeString(test, "ok");
            r.put("writeTest", "成功");
            Files.deleteIfExists(test);
        } catch (Exception e) {
            r.put("writeTest", e.getClass().getName() + ": " + e.getMessage());
        }
        return ResponseEntity.ok(r);
    }
}
