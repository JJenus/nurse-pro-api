# Nurse Schedule Pro API

Nurse Pro is a professional shift management API designed to schedule nursing shifts with fairness and prevent burnout through intelligent scheduling algorithms.

## Features

- **Smart Shift Scheduling**: AI-powered scheduling with fairness constraints
- **Shift Management**: Create, update, and manage nursing shifts
- **Staffing Optimization**: Automatic nurse assignment with workload balancing
- **Export Capabilities**: Generate PDF and Excel reports
- **Swap Requests**: Nurse shift swapping with approval workflow
- **RESTful API**: Clean, well-documented endpoints
- **JWT Security**: Secure authentication and authorization
- **OpenAPI Documentation**: Interactive API documentation

## API Documentation

### Base URLs
- **Development**: `http://localhost:8080`
- **Production**: `https://nursepro.alwaysdata.net`

### Interactive Documentation
The API includes comprehensive OpenAPI/Swagger documentation available at:
- `/swagger-ui.html` - Interactive API playground
- `/api-docs` - OpenAPI specification

### Authentication
All endpoints require JWT authentication:
```http
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6...
```

## API Structure

### Core Entities
- **Nurses**: Healthcare professionals with qualifications and preferences
- **Shifts**: Scheduled work periods (Day, Evening, Night)
- **Schedules**: Monthly shift arrangements
- **Swap Requests**: Shift exchange proposals
- **Workload Data**: Nurse workload tracking and analytics

### Main Endpoints

#### Schedules Management
```http
GET    /api/schedules                 # Get all schedules
POST   /api/schedules/generate        # Generate new schedule
GET    /api/schedules/{id}            # Get specific schedule
GET    /api/schedules/export          # Export schedules (PDF/Excel)
```

#### Shift Operations
```http
GET    /api/schedules/shifts          # Get all shifts
POST   /api/schedules/shifts          # Create new shift
PUT    /api/schedules/shifts/{id}     # Update shift
DELETE /api/schedules/shifts/{id}     # Delete shift
```

#### Nurse Management
```http
GET    /api/nurses                    # Get all nurses
POST   /api/nurses                    # Create new nurse
GET    /api/nurses/{id}               # Get specific nurse
PUT    /api/nurses/{id}               # Update nurse
DELETE /api/nurses/{id}               # Delete nurse
```

#### Swap Requests
```http
GET    /api/schedules/swap-requests           # Get all swap requests
POST   /api/schedules/swap-requests           # Create swap request
PUT    /api/schedules/swap-requests/{id}/approve  # Approve swap
PUT    /api/schedules/swap-requests/{id}/reject   # Reject swap
```

## Scheduling Algorithm

### Key Features
- **Fair Distribution**: Ensures equal shift distribution among nurses
- **Burnout Prevention**: Limits consecutive shifts and night shifts
- **Preference Matching**: Considers nurse shift preferences
- **Qualification Matching**: Ensures appropriate nurse qualifications per shift
- **Emergency Coverage**: Maintains minimum staffing requirements

### Constraints
- Max 3 consecutive day shifts
- Max 3 consecutive night shifts
- Minimum 2 nurses per shift
- Maximum 3 nurses per shift
- Target 14 shifts per nurse per month

## Export Features

### PDF Export
Generates professional PDF reports with:
- Color-coded shift types (Day, Evening, Night)
- Full nurse names and staffing ratios (removed)
- Understaffing highlights
- Weekly organized layout

### Excel Export
Creates Excel spreadsheets with:
- Multiple weekly sheets
- Text wrapping for nurse names
- Color-coded shift types
- Filterable and sortable data
- Professional formatting

## Installation & Setup

### Prerequisites
- Java 17 or higher
- Maven 3.6+
- PostgreSQL 17+ (or Docker Compose)

### Quick Start
1. **Clone the repository**
   ```bash
   git clone https://github.com/jjenus/nurse-pro-api.git
   cd nurse-pro-api
   ```

2. **Configure database**
   ```bash
   # Create the local PostgreSQL database
   psql -U postgres -c 'CREATE DATABASE nurse_db;'
   ```

   The `dev` profile defaults to `jdbc:postgresql://localhost:5432/nurse_db` with
   `postgres` / `postgres`. Override it with `DB_URL`, `DB_USERNAME`, and
   `DB_PASSWORD`. `docker compose up -d db` starts PostgreSQL 17 instead.

3. **Build and run**
   ```bash
   ./mvnw clean package -DskipTests
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```

4. **Access the API**
    - API: http://localhost:8080/api
    - Docs: http://localhost:8080/swagger-ui.html

## Configuration

### Environment Variables
```bash
export DB_URL=jdbc:postgresql://localhost:5432/nurse_db
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
export JWT_SECRET=your_jwt_secret
export PORT=8080
```

### Application Properties
```properties
# Server
server.port=${PORT:8080}

# Database
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/nurse_db}
spring.datasource.username=${DB_USERNAME:postgres}
spring.datasource.password=${DB_PASSWORD:postgres}

# JPA
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true

# JWT
jwt.secret=your-secret-key
jwt.expiration=86400000
```

## Deployment

Pushes to `main` run `.github/workflows/deploy-api.yml`: it builds
`target/nursepro.jar`, uploads it to `~/nurpro.jar` on AlwaysData, and refreshes
the JVM wrapper and runtime secrets. If `ALWAYSDATA_API_TOKEN` is configured it
also restarts the site; otherwise restart it manually from **Web > Sites** in
AlwaysData.

Repository secrets:

- `ALWAYSDATA_SSH_KEY` - required; private deploy key authorized on the account
- `DB_PASSWORD` - required; PostgreSQL password written to `~/.config/nursepro/env`
- `ALWAYSDATA_API_TOKEN` - optional; enables automatic restart

The AlwaysData user program runs `java -jar nurpro.jar --server.address=:: --server.port=$PORT`.
`~/.local/bin/java` must remain in place: it replaces the platform's
`JAVA_TOOL_OPTIONS` with a memory profile that avoids OOM-killing the JVM during
startup.

## Testing (Not available)

### Run Tests
```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=ScheduleServiceTest

# Run with coverage
mvn jacoco:report
```

### API Testing
```bash
# Using curl
curl -X GET "http://localhost:8080/api/schedules" \
  -H "Authorization: Bearer your-jwt-token"

# Using httpie
http GET :8080/api/schedules "Authorization:Bearer your-jwt-token"
```

## Dependencies

### Core Dependencies
- **Spring Boot 3.x** - Application framework
- **Spring Security** - Authentication and authorization
- **Spring Data JPA** - Database operations
- **PostgreSQL JDBC Driver** - Database driver
- **JWT** - JSON Web Token support
- **Lombok** - Reduced boilerplate code

### Export Dependencies
- **Apache POI** - Excel export functionality
- **iText PDF** - PDF export functionality

### Documentation
- **Springdoc OpenAPI** - API documentation
- **Swagger UI** - Interactive API explorer

## Contributing

We welcome contributions! Please see our [Contributing Guidelines](CONTRIBUTING.md) for details.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Support

- **Email**: support@nursepro.com
- **Website**: https://nursepro.com
- **Documentation**: https://docs.nursepro.com
- **Issues**: GitHub Issues

## Acknowledgments

- Nursing professionals worldwide
- Healthcare organizations using our system
- Open source community contributors
- Spring Boot development team

---

**Nurse Schedule Pro API** - Making healthcare scheduling fair and efficient since 2024.