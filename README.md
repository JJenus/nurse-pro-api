# Nurse Schedule Pro API

![Nurse Pro](https://img.shields.io/badge/Nurse-Pro%20API-blueviolet)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)
![Java](https://img.shields.io/badge/Java-17-orange)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

Nurse Pro is a professional shift management API designed to schedule nursing shifts with fairness and prevent burnout through intelligent scheduling algorithms.

## 🚀 Features

- **Smart Shift Scheduling**: AI-powered scheduling with fairness constraints
- **Shift Management**: Create, update, and manage nursing shifts
- **Staffing Optimization**: Automatic nurse assignment with workload balancing
- **Export Capabilities**: Generate PDF and Excel reports
- **Swap Requests**: Nurse shift swapping with approval workflow
- **RESTful API**: Clean, well-documented endpoints
- **JWT Security**: Secure authentication and authorization
- **OpenAPI Documentation**: Interactive API documentation

## 📋 API Documentation

### Base URLs
- **Development**: `http://localhost:8080`
- **Staging**: `https://staging.nursepro.com`
- **Production**: `https://api.nursepro.com`

### Interactive Documentation
The API includes comprehensive OpenAPI/Swagger documentation available at:
- `/swagger-ui.html` - Interactive API playground
- `/v3/api-docs` - OpenAPI specification

### Authentication
All endpoints require JWT authentication:
```http
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6...
```

## 🏗️ API Structure

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

## 🎯 Scheduling Algorithm

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

## 📊 Export Features

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

## 🛠️ Installation & Setup

### Prerequisites
- Java 17 or higher
- Maven 3.6+
- MySQL 8.0+

### Quick Start
1. **Clone the repository**
   ```bash
   git clone https://github.com/jjenus/nurse-pro-api.git
   cd nurse-pro-api
   ```

2. **Configure database**
   ```bash
   # Create MySQL database
   CREATE DATABASE nurse_pro_db;
   ```

3. **Update application properties**
   ```properties
   # src/main/resources/application.properties
   spring.datasource.url=jdbc:mysql://localhost:3306/nurse_pro_db
   spring.datasource.username=your_username
   spring.datasource.password=your_password
   ```

4. **Build and run**
   ```bash
   mvn clean install
   mvn spring-boot:run
   ```

5. **Access the API**
    - API: http://localhost:8080/api
    - Docs: http://localhost:8080/swagger-ui.html

## 🔧 Configuration

### Environment Variables
```bash
export DATABASE_URL=your_database_url
export DATABASE_USERNAME=your_username
export DATABASE_PASSWORD=your_password
export JWT_SECRET=your_jwt_secret
export API_PORT=8080
```

### Application Properties
```properties
# Server
server.port=8080

# Database
spring.datasource.url=jdbc:mysql://localhost:3306/nurse_pro_db
spring.datasource.username=root
spring.datasource.password=password

# JPA
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true

# JWT
jwt.secret=your-secret-key
jwt.expiration=86400000
```

## 🧪 Testing (Not available)

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

## 📦 Dependencies

### Core Dependencies
- **Spring Boot 3.x** - Application framework
- **Spring Security** - Authentication and authorization
- **Spring Data JPA** - Database operations
- **MySQL Connector** - Database driver
- **JWT** - JSON Web Token support
- **Lombok** - Reduced boilerplate code

### Export Dependencies
- **Apache POI** - Excel export functionality
- **iText PDF** - PDF export functionality

### Documentation
- **Springdoc OpenAPI** - API documentation
- **Swagger UI** - Interactive API explorer

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guidelines](CONTRIBUTING.md) for details.

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## 🆘 Support

- **Email**: support@nursepro.com
- **Website**: https://nursepro.com
- **Documentation**: https://docs.nursepro.com
- **Issues**: GitHub Issues

## 🙏 Acknowledgments

- Nursing professionals worldwide
- Healthcare organizations using our system
- Open source community contributors
- Spring Boot development team

---

**Nurse Schedule Pro API** - Making healthcare scheduling fair and efficient since 2024.