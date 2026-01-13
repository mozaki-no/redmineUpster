package mozaki.redmineUpster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import mozaki.redmineUpster.config.RedmineProperties;

@SpringBootApplication
@EnableConfigurationProperties(RedmineProperties.class)
public class RedmineUpsterApplication {

	public static void main(String[] args) {
		SpringApplication.run(RedmineUpsterApplication.class, args);
	}

}
