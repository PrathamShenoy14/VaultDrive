# VaultDrive

VaultDrive is a production-minded cloud file storage and sharing platform inspired by Google Drive.

The project focuses on learning backend engineering, system design, database management, authentication, security, and cloud storage architecture.

VaultDrive is being developed incrementally, starting with a Spring Boot backend. A Next.js web application and a React Native mobile application are planned for later phases.

## Tech Stack

### Backend
- Java 21
- Spring Boot 4.1.1
- Spring Security
- Spring Data JPA / Hibernate
- Spring Boot OAuth2 Resource Server
- Maven

### Database
- PostgreSQL 17
- Flyway database migrations

### Infrastructure
- Docker
- Ubuntu Server (VirtualBox development environment)

### Authentication
- BCrypt password hashing
- JWT authentication (HS256)
- Stateless authentication using bearer tokens

### Testing
- JUnit 5
- Mockito
- Spring Boot Test
- MockMvc
- Spring Security integration tests

## Implemented Features

### 1. User Registration

- REST API for user registration
- Request validation
- BCrypt password hashing
- Case-insensitive email uniqueness
- PostgreSQL persistence using Spring Data JPA
- Flyway database migrations
- Centralized exception handling
- Unit, repository, and controller tests

### 2. User Login

- REST API for user login
- Email and password authentication
- Case-insensitive email lookup
- Password verification using BCrypt
- Consistent error responses for invalid credentials
- Automated authentication tests

### 3. JWT Authentication

- JWT access token generation
- HS256 token signing
- Configurable token expiration
- Token signature verification
- Issuer and expiration validation
- Stateless authentication using Spring Security
- Bearer token authentication
- Protected REST endpoints
- Automated JWT and Spring Security integration tests

### 4. Database Management

- PostgreSQL running inside a Docker container
- Database schema management using Flyway
- JPA entity mapping
- Repository-level integration testing

### 5. API Security

- Public registration and login endpoints
- Protected endpoints requiring JWT authentication
- Centralized authentication and validation error handling
- HTTP 401 responses for unauthenticated requests

## API Endpoints

The following endpoints are currently implemented.

| Method | Endpoint | Authentication | Description |
|--------|----------|----------------|-------------|
| GET | `/api/v1/health` | Public | Check API health |
| POST | `/api/v1/auth/register` | Public | Register a new user |
| POST | `/api/v1/auth/login` | Public | Authenticate and receive a JWT |
| GET | `/api/v1/users/me` | Required | Retrieve the authenticated user's UUID |

### Authentication Flow

1. A user registers using an email, password, and display name.
2. The password is hashed using BCrypt before being stored.
3. The user logs in using their registered credentials.
4. After successful authentication, the backend generates a signed JWT.
5. The client includes the JWT in the `Authorization` header when accessing protected endpoints.
6. Spring Security validates the JWT before allowing access.

Example authorization header:

```http
Authorization: Bearer <access_token>
```

Access tokens currently expire after 15 minutes by default.

## Getting Started

### Prerequisites

- Java 21
- PostgreSQL 17
- Maven or the included Maven wrapper
- Docker (optional, for running PostgreSQL)

The current development environment uses PostgreSQL 17 in a Docker container running inside an Ubuntu Server virtual machine.

Any compatible PostgreSQL 17 instance can be used with the appropriate database configuration.

### 1. Clone the Repository

```bash
git clone <repository-url>
cd VaultDrive/backend
```

Replace `<repository-url>` with the actual GitHub repository URL.

### 2. Configure PostgreSQL

Create a PostgreSQL database and user matching the backend's datasource configuration.

Ensure PostgreSQL is running and accessible from the backend.

Database schema changes are managed automatically through Flyway migrations when the application starts.

### 3. Configure Environment Variables

The backend requires the following environment variables:

| Variable | Description |
|----------|-------------|
| `DB_PASSWORD` | PostgreSQL database password |
| `JWT_SECRET` | Base64-encoded secret key used to sign JWTs |

The JWT secret must decode to at least 32 bytes.

Generate a development secret using PowerShell:

```powershell
$key = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($key)
[Convert]::ToBase64String($key)
```

Set the environment variables in your PowerShell terminal:

```powershell
$env:DB_PASSWORD="your_database_password"
$env:JWT_SECRET="your_generated_base64_secret"
```

Never commit database credentials, JWT secrets, or environment files containing sensitive information.

### 4. Run the Backend

Navigate to the backend directory:

```powershell
cd backend
```

If you are already inside the backend directory, skip this step.

Start the application:

```powershell
.\mvnw spring-boot:run
```

The API will be available at:

```text
http://localhost:8080
```

Verify that the application is running:

```http
GET http://localhost:8080/api/v1/health
```

Expected response:

```json
{
  "status": "UP",
  "service": "vaultdrive-api"
}
```

### 5. Run Tests

Ensure PostgreSQL is running and the required environment variables are configured.

Execute:

```powershell
.\mvnw test
```

The test suite includes:

- Registration service unit tests
- Authentication service unit tests
- Controller tests
- Repository integration tests
- JWT generation and validation tests
- Spring Security integration tests

Some integration tests require access to the configured PostgreSQL database.

## Project Structure

The backend follows a feature-based package structure.

```text
backend/
├── src/
│   ├── main/
│   │   ├── java/com/vaultdrive/
│   │   │   ├── auth/
│   │   │   │   ├── config/
│   │   │   │   ├── dto/
│   │   │   │   ├── exception/
│   │   │   │   ├── AuthenticationService.java
│   │   │   │   ├── AuthController.java
│   │   │   │   └── RegistrationService.java
│   │   │   ├── common/
│   │   │   │   └── exception/
│   │   │   ├── security/
│   │   │   │   ├── JwtConfig.java
│   │   │   │   ├── JwtService.java
│   │   │   │   └── SecurityConfig.java
│   │   │   ├── user/
│   │   │   │   ├── User.java
│   │   │   │   ├── UserController.java
│   │   │   │   └── UserRepository.java
│   │   │   └── VaultDriveApplication.java
│   │   └── resources/
│   │       ├── db/migration/
│   │       └── application.properties
│   └── test/
└── pom.xml
```

## Planned Features

### File Management

- File uploads and downloads
- Folder creation and management
- File renaming and moving
- File deletion and restoration
- File metadata management
- Object storage integration

### Sharing and Permissions

- Secure file-sharing links
- User-based sharing
- File and folder permissions
- Access control and ownership validation

### Performance and Background Processing

- Redis caching
- Message queues
- Asynchronous background processing
- Notifications
- Activity logging

### Frontend and Mobile

- Next.js web application
- React Native mobile application using the same backend APIs

### Additional Security

- Authentication rate limiting
- Account-state validation
- Token revocation and logout strategy
- Secret rotation
- Additional authorization and security testing

These features are planned and have not yet been implemented.

## Development Status

**Completed:**
- Backend project setup
- PostgreSQL integration
- Flyway migrations
- User registration
- User login
- JWT generation and validation
- Spring Security authentication
- Protected endpoint implementation
- Authentication and security tests

**Current milestone:**

Designing and implementing file and folder management, including the separation of file metadata in PostgreSQL from actual file contents in object storage.

## Project Goals

VaultDrive is designed as a hands-on backend engineering and system design learning project.

The primary goals are to:

- Understand the design decisions behind a cloud storage platform.
- Build secure and maintainable REST APIs.
- Apply database design and migration practices.
- Learn object storage and file transfer architecture.
- Explore caching, message queues, and asynchronous processing.
- Understand trade-offs between different architectural approaches.
- Build a foundation that can support both web and mobile clients.