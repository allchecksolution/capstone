# Security Conventions

## Goal

Manage authentication and authorization using Spring Security and JWT.

---

## Authentication & Token Security

- **MUST — Trusted Authentication:** Authenticate protected APIs on the server and resolve the acting principal from the authenticated context. Validate external identity-provider credentials before issuing application credentials.
- **MUST — JWT Validation:** Validate JWT signatures, expiration times, allowed algorithms, and required issuer and audience information.
- **MUST — Token Lifecycle:** Separate the purposes of Access Tokens and Refresh Tokens, and apply appropriate expiration periods and Refresh Token reissuance and revocation policies.
- **MUST — Credential Protection:** Store passwords using secure hashing algorithms, and do not expose JWT signing keys or authentication secrets in source code or repositories.

---

## Authorization

- **MUST — Least Privilege:** Grant only the permissions required for each operation, and deny access unless explicitly permitted.
- **MUST — Resource-Level Authorization:** Verify resource ownership and operation-specific permissions against current server-side state, beyond role or token claims. Replayed responses require current access to the stored result.

---

## API & Web Security

- **MUST — Injection Prevention:** Use parameter binding when constructing SQL/JPQL queries, and do not directly concatenate untrusted input into executable statements.
- **MUST — Secure Communication:** Transmit authentication credentials and sensitive data over HTTPS.
- **SHOULD — Authentication Abuse Prevention:** Apply measures such as rate limiting to login and token reissuance APIs to prevent brute-force attacks and abuse.
- **SHOULD — Web Security:** When browser-based clients are supported, restrict CORS to the necessary scope, and apply CSRF protection when using automatically transmitted authentication credentials such as cookies.

---

## Sensitive Data Protection

- **MUST — Sensitive Data Protection:** Collect, process, and disclose sensitive data only to the extent necessary. Keep sensitive files private and serve them through authorized access.
