package com.demo.upimesh.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@Controller
public class DashboardController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", "UP", "mode", "demo"));
    }

    @GetMapping("/")
    public String home() {
        return "dashboard";
    }
}
