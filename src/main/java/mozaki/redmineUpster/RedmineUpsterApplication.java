package mozaki.redmineUpster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import mozaki.redmineUpster.config.RedmineProperties;
import mozaki.redmineUpster.config.SyncConfigProperties;
import mozaki.redmineUpster.config.TrustStoreSetup;

@SpringBootApplication
@EnableConfigurationProperties({RedmineProperties.class, SyncConfigProperties.class})
public class RedmineUpsterApplication {

	public static void main(String[] args) {
		// 社内CAの Redmine に接続できるよう、Windows では Windows の証明書ストアも使う
		TrustStoreSetup.configure();
		SpringApplication.run(RedmineUpsterApplication.class, args);
	}

}
