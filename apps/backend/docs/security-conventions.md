# Security Conventions

## Goal

Security in ALLCHECK Backend aims to ensure **secure authentication and authorization, least privilege, sensitive data protection, and prevention of major security vulnerabilities**.

Manage security using Spring Security and JWT, and verify authentication and authorization information on the server without trusting the client.

---

## Authentication & Token Security

- **MUST — Secure Authentication:** Authenticate all protected APIs on the server, and issue application-specific authentication credentials only after validating the identity provider's tokens and user information for social login.
- **MUST — JWT Validation:** Validate JWT signatures, expiration times, allowed algorithms, and required issuer and audience information.
- **MUST — Token Lifecycle:** Separate the purposes of Access Tokens and Refresh Tokens, and apply appropriate expiration periods and Refresh Token reissuance and revocation policies.
- **MUST — Credential Protection:** Store administrator passwords using secure hashing algorithms, and do not expose JWT signing keys or authentication secrets in source code or repositories.

---

## Authorization

- **MUST — Least Privilege:** Grant only the minimum permissions required for `ADMIN` and `DRIVER` roles, and deny access unless explicitly permitted.
- **MUST — Resource-Level Authorization:** Verify resource ownership and accessible states on the server, in addition to roles, to prevent IDOR/BOLA vulnerabilities.
- **SHOULD — Centralized Authorization:** Manage common authentication and authorization policies in Spring Security, and verify detailed business permissions in the appropriate layers.

---

## API & Web Security

- **MUST — Injection Prevention:** Use parameter binding when constructing SQL/JPQL queries, and do not directly concatenate untrusted input into executable statements.
- **MUST — Secure Communication:** Transmit authentication credentials and sensitive data over HTTPS.
- **SHOULD — Authentication Abuse Prevention:** Apply measures such as rate limiting to login and token reissuance APIs to prevent brute-force attacks and abuse.
- **SHOULD — Web Security:** When browser-based clients are supported, restrict CORS to the necessary scope, and apply CSRF protection when using automatically transmitted authentication credentials such as cookies.

---

## Sensitive Data Protection

- **MUST — Sensitive Data Protection:** Collect, process, and disclose personal information, such as phone numbers and GPS location data, only to the extent necessary, and prevent unauthorized access and unnecessary exposure.
