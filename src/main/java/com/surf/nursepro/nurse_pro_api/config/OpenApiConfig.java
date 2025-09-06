package com.surf.nursepro.nurse_pro_api.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Nurse Schedule Pro API",
                version = "1.0",
                description = "Nurse Pro is a professional shift management app, "
                        + "aims at scheduling shifts with fairness and prevent burnout.",
                contact = @Contact(
                        name = "Nurse Pro Dev Team",
                        email = "support@nursepro.com",
                        url = "https://nursepro.com"
                ),
                license = @License(
                        name = "Apache 2.0",
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                )
        ),
        servers = {
                @Server(url = "http://localhost:8080", description = "Local Development Server"),
                @Server(url = "https://api.nursepro.com", description = "Production Server"),
                @Server(url = "https://nurse-pro-api.onrender.com", description = "Staging Server"),
        },
        security = {
                @SecurityRequirement(name = "bearerAuth")
        }
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Provide the JWT token. Example: 'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6...'"
)
public class OpenApiConfig {
}
