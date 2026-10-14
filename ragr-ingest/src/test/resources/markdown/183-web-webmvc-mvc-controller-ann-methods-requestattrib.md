---
title: "@RequestAttribute"
project: "Spring Framework"
version: "7.0.9"
stack: "Spring Boot 4.1.1"
section: "Overview > Web on Servlet Stack > Spring Web MVC > Annotated Controllers > Handler Methods"
online_url: "https://docs.spring.io/spring-framework/reference/7.0/web/webmvc/mvc-controller/ann-methods/requestattrib.html"
source: "github.com/spring-projects/spring-framework (v7.0.9, framework-docs)"
---

# `@RequestAttribute`

See equivalent in the Reactive stack

Similar to `@SessionAttribute`, you can use the `@RequestAttribute` annotations to access pre-existing request attributes created earlier (for example, by a Servlet `Filter` or `HandlerInterceptor`):

**Java**

```java
@GetMapping("/")
public String handle(@RequestAttribute Client client) { (1)
    // ...
}
```

**(1)** Using the `@RequestAttribute` annotation.

**Kotlin**

```kotlin
@GetMapping("/")
fun handle(@RequestAttribute client: Client): String { (1)
    // ...
}
```

**(1)** Using the `@RequestAttribute` annotation.
