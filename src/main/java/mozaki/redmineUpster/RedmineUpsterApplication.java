package mozaki.redmineUpster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import mozaki.redmineUpster.config.RedmineProperties;
import mozaki.redmineUpster.config.SyncConfigProperties;
import mozaki.redmineUpster.config.TrustStoreSetup;
import mozaki.redmineUpster.settings.SettingsLauncher;

@SpringBootApplication
@EnableConfigurationProperties({RedmineProperties.class, SyncConfigProperties.class})
public class RedmineUpsterApplication {

	public static void main(String[] args) {
		// 設定画面（--settings）は Spring を起動せずにすぐ開く
		if (SettingsLauncher.isRequested(args)) {
			int exitCode = SettingsLauncher.launch(args);
			if (exitCode != 0) {
				System.exit(exitCode);
			}
			return;
		}
		// 社内CAの Redmine に接続できるよう、Windows では Windows の証明書ストアも使う
		TrustStoreSetup.configure();
		SpringApplication.run(RedmineUpsterApplication.class, args);
	}

}
