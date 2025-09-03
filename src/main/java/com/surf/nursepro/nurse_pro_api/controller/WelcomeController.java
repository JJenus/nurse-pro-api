package com.surf.nursepro.nurse_pro_api.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WelcomeController {

    @Value("${spring.application.name:NursePro API}")
    private String appName;

    @Value("${spring.application.version:1.0.0}")
    private String appVersion;

    @GetMapping("/")
    public String welcome() {
        return """
            <html>
              <head>
                <title>%s</title>
                <style>
                  body { font-family: Arial, sans-serif; text-align: center; padding: 50px; }
                  h1 { color: #2c3e50; }
                  .info { margin: 20px 0; }
                  a.button {
                    display: inline-block;
                    padding: 12px 24px;
                    margin-top: 20px;
                    background-color: #007bff;
                    color: #fff;
                    text-decoration: none;
                    border-radius: 6px;
                    font-weight: bold;
                  }
                  a.button:hover { background-color: #0056b3; }
                </style>
              </head>
              <body>
                <h1>Welcome to %s</h1>
                <div class="info">
                  <p>Version: %s</p>
                  <p>Status: <span style='color:green;'>Running</span></p>
                </div>
                <a class="button" href="/swagger-ui/index.html">View API Docs</a>
              </body>
            </html>
            """.formatted(appName, appName, appVersion);
    }
}
