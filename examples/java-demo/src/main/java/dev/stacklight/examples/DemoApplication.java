package dev.stacklight.examples;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A small application that fails on purpose.
 *
 * <p>There is no Stacklight code in this class, and that is the point of the starter: put
 * it on the classpath, set two properties, and exceptions reaching a controller are
 * reported without the application saying anything about it.
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
