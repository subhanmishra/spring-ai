---
title: "Authorization Changes"
project: "Spring Security"
version: "7.1.1"
stack: "Spring Boot 4.1.1"
section: "Overview > Migrating to 7 > Servlet"
online_url: "https://docs.spring.io/spring-security/reference/7.1/migration/servlet/authorization.html"
source: "github.com/spring-projects/spring-security (7.1.1, docs)"
---

# Authorization Changes

## If Using Access API, Add `spring-security-access`

Spring Security 7 moves `AccessDecisionManager`, `AccessDecisionVoter`, and the related Access API to a legacy module, `spring-security-access`. The Access API is deprecated in favor of the Authorization API as of Spring Security 5.

You can add the dependency like other Spring Security dependencies like so:

**Maven**

```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-access</artifactId>
</dependency>
```

**Gradle**

```groovy
implementation('org.springframework.security:spring-security-access')
```
