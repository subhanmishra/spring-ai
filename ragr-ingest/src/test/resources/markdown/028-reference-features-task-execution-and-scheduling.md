---
title: "Task Execution and Scheduling"
project: "Spring Boot"
version: "4.1.1"
stack: "Spring Boot 4.1.1"
section: "Overview > Reference > Core Features"
online_url: "https://docs.spring.io/spring-boot/4.1/reference/features/task-execution-and-scheduling.html"
source: "github.com/spring-projects/spring-boot (v4.1.1, documentation/spring-boot-docs)"
---

# Task Execution and Scheduling

In the absence of an `Executor` bean in the context, Spring Boot auto-configures an `AsyncTaskExecutor`. When virtual threads are enabled (using Java 21+ and `spring.threads.virtual.enabled` set to `true`) this will be a `SimpleAsyncTaskExecutor` that uses virtual threads. Otherwise, it will be a `ThreadPoolTaskExecutor` with sensible defaults.

The auto-configured `AsyncTaskExecutor` is used for the following integrations unless a custom `Executor` bean is defined:

- Execution of asynchronous tasks using `@EnableAsync`, unless a bean of type `AsyncConfigurer` is defined.

- Asynchronous handling of `Callable` return values from controller methods in Spring for GraphQL.

- Asynchronous request handling in Spring MVC.

- Support for blocking execution in Spring WebFlux.

- Utilized for inbound and outbound message channels in Spring WebSocket.

- Bootstrap executor for JPA, based on the bootstrap mode of JPA repositories.

- Bootstrap executor for [background initialization](https://docs.spring.io/spring-framework/reference/7.0/core/beans/java/composing-configuration-classes.html#beans-java-startup-background) of beans in the `ApplicationContext`.

While this approach works in most scenarios, Spring Boot allows you to override the auto-configured `AsyncTaskExecutor`. By default, when a custom `Executor` bean is registered, the auto-configured `AsyncTaskExecutor` backs off, and the custom `Executor` is used for regular task execution (via `@EnableAsync`).

However, Spring MVC, Spring WebFlux, and Spring GraphQL all require a bean named `applicationTaskExecutor`. For Spring MVC and Spring WebFlux, this bean must be of type `AsyncTaskExecutor`, whereas Spring GraphQL does not enforce this type requirement.

Spring WebSocket and JPA will use `AsyncTaskExecutor` if either a single bean of this type is available or a bean named `applicationTaskExecutor` is defined.

Finally, the boostrap executor of the `ApplicationContext` uses a bean named `applicationTaskExecutor` unless a bean named `bootstrapExecutor` is defined.

The following code snippet demonstrates how to register a custom `AsyncTaskExecutor` to be used with Spring MVC, Spring WebFlux, Spring GraphQL, Spring WebSocket, JPA, and background initialization of beans.

**Java**

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class MyTaskExecutorConfiguration {

    @Bean("applicationTaskExecutor")
    SimpleAsyncTaskExecutor applicationTaskExecutor() {
        return new SimpleAsyncTaskExecutor("app-");
    }

}
```

**Kotlin**

```kotlin
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.SimpleAsyncTaskExecutor

@Configuration(proxyBeanMethods = false)
class MyTaskExecutorConfiguration {

    @Bean("applicationTaskExecutor")
    fun applicationTaskExecutor(): SimpleAsyncTaskExecutor {
        return SimpleAsyncTaskExecutor("app-")
    }

}
```

> **NOTE:**
>
> The `applicationTaskExecutor` bean will also be used for regular task execution if there is no `@Primary` bean or a bean named `taskExecutor` of type `Executor` or `AsyncConfigurer` present in the application context.

> **WARNING:**
>
> If neither the auto-configured `AsyncTaskExecutor` nor the `applicationTaskExecutor` bean is defined, the application defaults to a bean named `taskExecutor` for regular task execution (`@EnableAsync`), following Spring Framework’s behavior. However, this bean will not be used for Spring MVC, Spring WebFlux, Spring GraphQL. It could, however, be used for Spring WebSocket or JPA if the bean’s type is `AsyncTaskExecutor`.

If your application needs multiple `Executor` beans for different integrations, such as one for regular task execution with `@EnableAsync` and other for Spring MVC, Spring WebFlux, Spring WebSocket and JPA, you can configure them as follows.

**Java**

```java
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class MyTaskExecutorConfiguration {

    @Bean("applicationTaskExecutor")
    SimpleAsyncTaskExecutor applicationTaskExecutor() {
        return new SimpleAsyncTaskExecutor("app-");
    }

    @Bean("taskExecutor")
    ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
        threadPoolTaskExecutor.setThreadNamePrefix("async-");
        return threadPoolTaskExecutor;
    }

}
```

**Kotlin**

```kotlin
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration(proxyBeanMethods = false)
class MyTaskExecutorConfiguration {

    @Bean("applicationTaskExecutor")
    fun applicationTaskExecutor(): SimpleAsyncTaskExecutor {
        return SimpleAsyncTaskExecutor("app-")
    }

    @Bean("taskExecutor")
    fun taskExecutor(): ThreadPoolTaskExecutor {
        val threadPoolTaskExecutor = ThreadPoolTaskExecutor()
        threadPoolTaskExecutor.setThreadNamePrefix("async-")
        return threadPoolTaskExecutor
    }

}
```

> **TIP:**
>
> The auto-configured `ThreadPoolTaskExecutorBuilder` or `SimpleAsyncTaskExecutorBuilder` allow you to easily create instances of type `AsyncTaskExecutor` that replicate the default behavior of auto-configuration.
>
> **Java**
>
> ``` java
> import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder;
> import org.springframework.context.annotation.Bean;
> import org.springframework.context.annotation.Configuration;
> import org.springframework.core.task.SimpleAsyncTaskExecutor;
>
> @Configuration(proxyBeanMethods = false)
> public class MyTaskExecutorConfiguration {
>
>     @Bean
>     SimpleAsyncTaskExecutor taskExecutor(SimpleAsyncTaskExecutorBuilder builder) {
>         return builder.build();
>     }
>
> }
> ```
>
> **Kotlin**
>
> ``` kotlin
> import org.springframework.boot.task.SimpleAsyncTaskExecutorBuilder
> import org.springframework.context.annotation.Bean
> import org.springframework.context.annotation.Configuration
> import org.springframework.core.task.SimpleAsyncTaskExecutor
>
> @Configuration(proxyBeanMethods = false)
> class MyTaskExecutorConfiguration {
>
>     @Bean
>     fun taskExecutor(builder: SimpleAsyncTaskExecutorBuilder): SimpleAsyncTaskExecutor {
>         return builder.build()
>     }
>
> }
> ```

If a `taskExecutor` named bean is not an option, you can mark your bean as `@Primary` or define an `AsyncConfigurer` bean to specify the `Executor` responsible for handling regular task execution with `@EnableAsync`. The following example demonstrates how to achieve this.

**Java**

```java
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;

@Configuration(proxyBeanMethods = false)
public class MyTaskExecutorConfiguration {

    @Bean
    AsyncConfigurer asyncConfigurer(ExecutorService executorService) {
        return new AsyncConfigurer() {

            @Override
            public Executor getAsyncExecutor() {
                return executorService;
            }

        };
    }

    @Bean
    ExecutorService executorService() {
        return Executors.newCachedThreadPool();
    }

}
```

**Kotlin**

```kotlin
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.AsyncConfigurer
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration(proxyBeanMethods = false)
class MyTaskExecutorConfiguration {

    @Bean
    fun asyncConfigurer(executorService: ExecutorService): AsyncConfigurer {
        return object : AsyncConfigurer {
            override fun getAsyncExecutor(): Executor {
                return executorService
            }
        }
    }

    @Bean
    fun executorService(): ExecutorService {
        return Executors.newCachedThreadPool()
    }

}
```

To register a custom `Executor` while keeping the auto-configured `AsyncTaskExecutor`, you can create a custom `Executor` bean and set the `defaultCandidate=false` attribute in its `@Bean` annotation, as demonstrated in the following example:

**Java**

```java
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MyTaskExecutorConfiguration {

    @Bean(defaultCandidate = false)
    @Qualifier("scheduledExecutorService")
    ScheduledExecutorService scheduledExecutorService() {
        return Executors.newSingleThreadScheduledExecutor();
    }

}
```

**Kotlin**

```kotlin
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

@Configuration(proxyBeanMethods = false)
class MyTaskExecutorConfiguration {

    @Bean(defaultCandidate = false)
    @Qualifier("scheduledExecutorService")
    fun scheduledExecutorService(): ScheduledExecutorService {
        return Executors.newSingleThreadScheduledExecutor()
    }

}
```

In that case, you will be able to autowire your custom `Executor` into other components while retaining the auto-configured `AsyncTaskExecutor`. However, remember to use the `@Qualifier` annotation alongside `@Autowired`.

If this is not possible for you, you can request Spring Boot to auto-configure an `AsyncTaskExecutor` anyway, as follows:

    spring:
      task:
        execution:
          mode: force

The auto-configured `AsyncTaskExecutor` will be used automatically for all integrations, even if a custom `Executor` bean is registered, including those marked as `@Primary`. These integrations include:

- Asynchronous task execution (`@EnableAsync`), unless an `AsyncConfigurer` bean is present.

- Spring for GraphQL’s asynchronous handling of `Callable` return values from controller methods.

- Spring MVC’s asynchronous request processing.

- Spring WebFlux’s blocking execution support.

- Utilized for inbound and outbound message channels in Spring WebSocket.

- Bootstrap executor for JPA, based on the bootstrap mode of JPA repositories.

- Bootstrap executor for [background initialization](https://docs.spring.io/spring-framework/reference/7.0/core/beans/java/composing-configuration-classes.html#beans-java-startup-background) of beans in the `ApplicationContext`, unless a bean named `bootstrapExecutor` is defined.

> **TIP:**
>
> Depending on your target arrangement, you could set `spring.task.execution.mode` to `force` to auto-configure an `applicationTaskExecutor`, change your `Executor` into an `AsyncTaskExecutor` or define both an `AsyncTaskExecutor` and an `AsyncConfigurer` wrapping your custom `Executor`.

> **WARNING:**
>
> When `force` mode is enabled, `applicationTaskExecutor` will also be configured for regular task execution with `@EnableAsync`, even if a `@Primary` bean or a bean named `taskExecutor` of type `Executor` is present. The only way to override the `Executor` for regular tasks is by registering an `AsyncConfigurer` bean.

When a `ThreadPoolTaskExecutor` is auto-configured, the thread pool uses 8 core threads that can grow and shrink according to the load. Those default settings can be fine-tuned using the `spring.task.execution` namespace, as shown in the following example:

    spring:
      task:
        execution:
          pool:
            max-size: 16
            queue-capacity: 100
            keep-alive: "10s"

This changes the thread pool to use a bounded queue so that when the queue is full (100 tasks), the thread pool increases to maximum 16 threads. Shrinking of the pool is more aggressive as threads are reclaimed when they are idle for 10 seconds (rather than 60 seconds by default).

A scheduler can also be auto-configured if it needs to be associated with scheduled task execution (using `@EnableScheduling` for instance).

If virtual threads are enabled (using Java 21+ and `spring.threads.virtual.enabled` set to `true`) this will be a `SimpleAsyncTaskScheduler` that uses virtual threads. This `SimpleAsyncTaskScheduler` will ignore any pooling related properties.

If virtual threads are not enabled, it will be a `ThreadPoolTaskScheduler` with sensible defaults. The `ThreadPoolTaskScheduler` uses one thread by default and its settings can be fine-tuned using the `spring.task.scheduling` namespace, as shown in the following example:

    spring:
      task:
        scheduling:
          thread-name-prefix: "scheduling-"
          pool:
            size: 2

A `ThreadPoolTaskExecutorBuilder` bean, a `SimpleAsyncTaskExecutorBuilder` bean, a `ThreadPoolTaskSchedulerBuilder` bean and a `SimpleAsyncTaskSchedulerBuilder` are made available in the context if a custom executor or scheduler needs to be created. The `SimpleAsyncTaskExecutorBuilder` and `SimpleAsyncTaskSchedulerBuilder` beans are auto-configured to use virtual threads if they are enabled (using Java 21+ and `spring.threads.virtual.enabled` set to `true`).
