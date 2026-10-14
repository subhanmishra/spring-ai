---
title: "Spring Boot 4 class relocations: org.springframework.boot.actuate.amqp (1 types)"
project: "Troubleshooting: upgrade breakages"
stack: "Spring Boot 3.5 -> 4.1 / Spring Framework 6.2 -> 7.0"
kind: "upgrade-map"
symptoms: "cannot find symbol; class not found after upgrading to Spring Boot 4"
---
# Spring Boot 4 class relocations: org.springframework.boot.actuate.amqp (1 types)

Spring Boot 3.5 types under `org.springframework.boot.actuate.amqp` and where they live in Boot 4.1. Derived from the 3.5.16 and 4.1.1 sources.

| Boot 3.5 type | Boot 4.1 type | Module / notes |
|---|---|---|
| `org.springframework.boot.actuate.amqp.RabbitHealthIndicator` | `org.springframework.boot.amqp.health.RabbitHealthIndicator` | module `spring-boot-amqp` (starter `spring-boot-starter-amqp`) |
