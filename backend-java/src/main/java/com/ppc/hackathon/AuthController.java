package com.ppc.hackathon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private static final String JSON_PATH = "users.json";
    private final ObjectMapper mapper = new ObjectMapper();

    // Citește JSON-ul
    private Map<String, Object> readJson() throws Exception {
        File file = new File(JSON_PATH);
        if (!file.exists()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("companies", new ArrayList<>());
            mapper.writeValue(file, empty);
        }
        return mapper.readValue(file, Map.class);
    }

    // Scrie JSON-ul
    private void writeJson(Map<String, Object> data) throws Exception {
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(JSON_PATH), data);
    }

    // === ÎNREGISTRARE ===
    // POST /api/auth/register
    // Body: { "companyName": "Enel Romania" }
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@RequestBody Map<String, String> body) {
        Map<String, String> response = new HashMap<>();
        try {
            String companyName = body.get("companyName");
            if (companyName == null || companyName.isBlank()) {
                response.put("status", "error");
                response.put("message", "Numele companiei este obligatoriu.");
                return ResponseEntity.badRequest().body(response);
            }

            Map<String, Object> data = readJson();
            List<Map<String, Object>> companies = (List<Map<String, Object>>) data.get("companies");

            // verifică dacă există deja
            boolean exists = companies.stream()
                .anyMatch(c -> companyName.equals(c.get("companyName")));
            if (exists) {
                response.put("status", "error");
                response.put("message", "Compania există deja.");
                return ResponseEntity.badRequest().body(response);
            }

            // generează token
            String token = "PPC-" + UUID.randomUUID().toString().toUpperCase().replace("-", "").substring(0, 16);

            Map<String, Object> newCompany = new HashMap<>();
            newCompany.put("companyName", companyName);
            newCompany.put("token", token);
            newCompany.put("assignedParks", new ArrayList<>());

            companies.add(newCompany);
            writeJson(data);

            response.put("status", "success");
            response.put("companyName", companyName);
            response.put("token", token);
            response.put("message", "Salvați tokenul! Nu va mai fi afișat.");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Eroare server: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // === LOGIN ===
    // POST /api/auth/login
    // Body: { "companyName": "Enel Romania", "token": "PPC-ABC123..." }
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            String companyName = body.get("companyName");
            String token = body.get("token");

            Map<String, Object> data = readJson();
            List<Map<String, Object>> companies = (List<Map<String, Object>>) data.get("companies");

            Optional<Map<String, Object>> found = companies.stream()
                .filter(c -> companyName.equals(c.get("companyName")) && token.equals(c.get("token")))
                .findFirst();

            if (found.isEmpty()) {
                response.put("status", "error");
                response.put("message", "Companie sau token invalid.");
                return ResponseEntity.status(401).body(response);
            }

            List<String> parks = (List<String>) found.get().getOrDefault("assignedParks", new ArrayList<>());

            response.put("status", "success");
            response.put("companyName", companyName);
            response.put("assignedParks", parks);
            response.put("role", "user");
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Eroare server: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
}