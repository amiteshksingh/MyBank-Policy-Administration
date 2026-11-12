# MyBank Policy Administration

A self-contained Spring Boot 3 application that provides a browser-based Policy Administration Point (PAP), H2 persistence, CRUD for policies/obligations/context attributes, a local policy simulator, audit records, and OPA bundle generation.

## Implemented capabilities

- Flexible dotted-path context attributes such as `subject.role`, `action.tool`, `resource.classification`, `context.ipAddress`, `context.country`, and `context.riskScore`
- Attribute-definition CRUD. Add new context dimensions without Java code changes
- Policy CRUD with `AND` or `OR` condition logic and priority
- Rego import for the documented policy-rule subset, including related obligations and inferred attributes
- Effects: `PERMIT`, `DENY`, and `PERMIT_WITH_OBLIGATIONS`
- Obligation CRUD with JSON parameters
- Operators: `EQ`, `NEQ`, `IN`, `NOT_IN`, `CONTAINS`, `STARTS_WITH`, `ENDS_WITH`, `GT`, `GTE`, `LT`, `LTE`, `CIDR`, `EXISTS`, and `NOT_EXISTS`
- Explicit-deny override in the Java simulator
- Downloadable OPA `bundle.tar.gz` containing `data.json` and `mybank/policy.rego`
- File-backed H2 database
- Browser UI served directly by Spring Boot, with no Node.js or Docker requirement

## Prerequisites

- Java 21
- Maven 3.9 or later
- VS Code
- Optional: OPA 1.x for bundle validation and runtime testing

Verify:

```bash
/usr/lib/jvm/java-21-openjdk-amd64/bin/java -version
mvn -version
```

If Maven uses another Java version:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
mvn -version
```

## Open and configure in VS Code

1. Extract the ZIP.
2. Open VS Code from the project directory:

```bash
cd MyBank-Policy-Administration
code .
```

3. Install these VS Code extensions:
   - Extension Pack for Java
   - Spring Boot Extension Pack
4. Allow VS Code to import the Maven project when prompted.
5. If Java 21 is not detected, create or update `.vscode/settings.json`:

```json
{
  "java.jdt.ls.java.home": "/usr/lib/jvm/java-21-openjdk-amd64",
  "java.configuration.runtimes": [
    {
      "name": "JavaSE-21",
      "path": "/usr/lib/jvm/java-21-openjdk-amd64",
      "default": true
    }
  ]
}
```

## Maven build and local run

```bash
mvn clean test
mvn clean package
mvn spring-boot:run
```

Or run the packaged JAR:

```bash
/usr/lib/jvm/java-21-openjdk-amd64/bin/java   -jar target/mybank-policy-administration.jar
```

Open `http://localhost:8080`.

## H2 local database setup

No separate H2 installation is required. On first startup, the application creates:

```text
./data/mybank-policy-db.mv.db
```

H2 console:

```text
/home/admin/.m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar

H2_JAR="$HOME/.m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar"

java -cp "$H2_JAR" org.h2.tools.Server \
  -web \
  -webAllowOthers \
  -webPort 8082
  
http://localhost:8080/h2-console
```

Connection values:

```text
JDBC URL: jdbc:h2:file:./data/mybank-policy-db
User:     mybank
Password: mybank
```

For client deployment, set a stronger password through environment variables or an external Spring profile. The project defaults are development-only.

## Basic use

1. Open **Attributes** and maintain available policy context keys.
2. Open **Obligations** and create actions such as masking, audit, approval, filtering, or watermarking.
3. Open **Policies**, select an effect, add any number of conditions, and attach obligations. Use **Import Rego** to import existing policies in the supported format below.
4. Open **Simulator**, provide nested JSON, and select **Evaluate**.
5. Select **Download OPA Bundle** to receive `MyBank-OPA-bundle.tar.gz`.

### Rego import format

The importer accepts a documented, intentionally small subset of Rego; it rejects unsupported syntax rather than silently dropping policy logic. Each policy must be a named top-level rule with one direct condition per line:

```rego
package mybank.imported
import rego.v1

permit_read_customer if {
  input.context.subject.role == "analyst"
  input.context.action.tool in ["read", "review"]
  input.context.resource.classification != null
}

deny_blocked_country if {
  input.context.context.country == "blocked"
}

permit_with_obligations_sensitive_access if {
  input.context.resource.classification == "sensitive"
}

obligations_sensitive_access := [
  {
    "code": "audit_sensitive_access",
    "name": "Audit sensitive access",
    "description": "Record access to sensitive resources",
    "parameters": {"event": "sensitive_access"}
  }
]
```

Supported rule names are `permit_<code>`, `deny_<code>`, and `permit_with_obligations_<code>`. Supported conditions are direct `input.context.<dotted.attribute>` comparisons using `==`, `!=`, or `in`; values must be JSON scalars, except `in`, which requires a JSON array of scalars. Comparing a path to `null` imports as an existence check. Conditions are combined with `AND`. A `permit_with_obligations_<code>` rule must have a matching `obligations_<code>` declaration containing an object or array of objects with string `code` and `name`, and optional `description` and `parameters` (a JSON object or array). Policy names are generated from their rule codes and priority defaults to 100. Newly referenced attributes are added automatically.

The importer also supports the decision-object format used by `default decision` plus `decision := { ... } if { ... }` rules. Decision objects must contain only string `decision`, array `obligations`, and string `reason` fields. The default must be `DENY`. Each non-default rule must have exactly one condition of the form `input.user.role == "ROLE"` and a decision of `PERMIT`, `DENY`, or `PERMIT_WITH_OBLIGATIONS`. Role values become policy codes, `reason` becomes the policy description, and obligation strings become obligation codes with generated names. As with the structured-rule format, other Rego expressions and fields are rejected. The default deny rule is represented by the application's existing default deny behavior.

All selected files are imported in one transaction. Duplicate or existing policy codes and unsupported Rego syntax are rejected. Existing obligations with matching codes are updated from the import. The UI accepts up to 10 `.rego` files of at most 1 MiB each.

Example simulator input:

```json
{
  "subject": { "role": "kyc_analyst" },
  "action": { "tool": "document_retrieval" },
  "resource": { "classification": "sensitive" },
  "context": {
    "ipAddress": "10.10.5.8",
    "country": "IN",
    "riskScore": 20
  }
}
```

## REST API

```text
GET/POST             /api/policies
GET/PUT/DELETE       /api/policies/{id}
POST                  /api/policies/import (multipart field: `files`, repeated for multiple .rego files)
GET/POST             /api/obligations
PUT/DELETE           /api/obligations/{id}
GET/POST             /api/attributes
PUT/DELETE           /api/attributes/{id}
POST                  /api/simulate
GET                   /api/bundle
GET                   /api/audit
```

## Validate and run the generated OPA bundle

Download the bundle from the UI or:

```bash
curl -fLo MyBank-OPA-bundle.tar.gz http://localhost:8080/api/bundle
```

Validate:

```bash
opa check MyBank-OPA-bundle.tar.gz
```

Run OPA:

```bash
opa run --server --bundle MyBank-OPA-bundle.tar.gz
```

Evaluate:

```bash
curl -s http://localhost:8181/v1/data/mybank/pbac/decision   -H 'Content-Type: application/json'   -d '{"input":{"context":{"subject":{"role":"kyc_analyst"},"action":{"tool":"document_retrieval"},"resource":{"classification":"sensitive"},"context":{"ipAddress":"10.10.5.8"}}}}'
```

## Linux service deployment without Docker

Build and copy:

```bash
mvn clean package
sudo mkdir -p /opt/mybank-policy-admin
sudo cp target/mybank-policy-administration.jar /opt/mybank-policy-admin/
sudo mkdir -p /var/lib/mybank-policy-admin
sudo chown -R "$USER":"$USER" /opt/mybank-policy-admin /var/lib/mybank-policy-admin
```

Create `/etc/systemd/system/mybank-policy-admin.service`:

```ini
[Unit]
Description=MyBank Policy Administration
After=network.target

[Service]
Type=simple
User=admin
WorkingDirectory=/var/lib/mybank-policy-admin
Environment=SPRING_DATASOURCE_URL=jdbc:h2:file:/var/lib/mybank-policy-admin/mybank-policy-db;AUTO_SERVER=TRUE;DB_CLOSE_ON_EXIT=FALSE
Environment=SPRING_DATASOURCE_USERNAME=mybank
Environment=SPRING_DATASOURCE_PASSWORD=CHANGE_THIS_PASSWORD
ExecStart=/usr/lib/jvm/java-21-openjdk-amd64/bin/java -jar /opt/mybank-policy-admin/mybank-policy-administration.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Activate:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now mybank-policy-admin
sudo systemctl status mybank-policy-admin
journalctl -u mybank-policy-admin -f
```

## Production notes

- Put the service behind the client's reverse proxy and TLS boundary.
- Disable or firewall the H2 console outside development.
- Replace development database credentials.
- Back up the H2 database before releases and policy migrations.
- Protect CRUD and bundle endpoints with the client's authentication and authorization standard before production use.
- The current UI supports one logical operator across a policy's conditions. Nested AND/OR groups can be added as a future schema extension.
- The Java simulator implements all listed operators. The generated Rego currently implements the core `EQ`, `NEQ`, `IN`, `EXISTS`, `NOT_EXISTS`, and `CIDR` operators. Any use of other operators should be validated before OPA deployment.

## Project structure

```text
src/main/java/com/mybank/pbac
  config/       seed data
  controller/   REST APIs
  dto/          API request/response models
  model/        H2/JPA entities and enums
  repository/   Spring Data repositories
  service/      policy CRUD, simulation, bundle generation, audit
src/main/resources
  static/       browser-based MyBank administration UI
  application.yml
```
