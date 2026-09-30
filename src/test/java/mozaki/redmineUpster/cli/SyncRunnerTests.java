package mozaki.redmineUpster.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import mozaki.redmineUpster.config.SyncConfigProperties;
import mozaki.redmineUpster.service.RedmineClientFactory;
import mozaki.redmineUpster.service.SpreadsheetParser;
import mozaki.redmineUpster.service.SyncConfigService;
import mozaki.redmineUpster.service.TicketIdWriter;

class SyncRunnerTests {

	@TempDir
	Path tempDir;

	@AfterEach
	void tearDown() {
		System.clearProperty("jpackage.app-path");
	}

	@Test
	@DisplayName("設定ファイルは カレントディレクトリ → 実行ファイルのフォルダ の順に探す（Windowsのexe直下）")
	void defaultConfigCandidates_exeFolder() {
		System.setProperty("jpackage.app-path", tempDir.resolve("redmineUpster.exe").toString());
		assertThat(SyncRunner.defaultConfigCandidates()).containsExactly(
				Paths.get("sync-config.yml").toAbsolutePath().normalize(),
				tempDir.resolve("sync-config.yml").toAbsolutePath().normalize());
	}

	@Test
	@DisplayName("Linuxのランチャー（bin/redmineUpster）ではアプリのルートフォルダを見る")
	void defaultConfigCandidates_linuxBinFolder() {
		System.setProperty("jpackage.app-path", tempDir.resolve("bin").resolve("redmineUpster").toString());
		assertThat(SyncRunner.defaultConfigCandidates())
				.contains(tempDir.resolve("sync-config.yml").toAbsolutePath().normalize());
	}

	@Test
	@DisplayName("テーブルにチケットID列がなければ、Redmineに接続する前にエラー終了する（sync.excel.table を設定から読む）")
	void tableWithoutTicketIdColumnFailsEarly() throws Exception {
		Path xlsx = tempDir.resolve("wbs.xlsx");
		try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(xlsx)) {
			wb.createSheet("WBS");
			XSSFSheet sheet = wb.createSheet("取込");
			Row header = sheet.createRow(1);
			header.createCell(0).setCellValue("トラッカー");
			header.createCell(1).setCellValue("大分類");
			Row row = sheet.createRow(2);
			row.createCell(0).setCellValue("サマリ");
			row.createCell(1).setCellValue("A");
			sheet.createTable(new AreaReference("A2:B3", SpreadsheetVersion.EXCEL2007)).setName("取込表");
			wb.write(out);
		}
		Path config = tempDir.resolve("sync-config.yml");
		Files.writeString(config, String.join("\n",
				"projects:",
				"  - name: t",
				"    default: true",
				"    redmine: { baseUrl: 'http://127.0.0.1:1', apiKey: k, projectId: p }",
				"    sync:",
				"      excel:",
				"        table: 取込表",
				""));
		SyncConfigService configService = new SyncConfigService(new SyncConfigProperties());
		configService.loadConfig(config.toString());
		assertThat(configService.getDefaultProject().orElseThrow().getSync().getExcel().getTable()).isEqualTo("取込表");

		RedmineClientFactory factory = Mockito.mock(RedmineClientFactory.class);
		SyncRunner runner = new SyncRunner(configService, new SpreadsheetParser(), new DiffCalculator(),
				Mockito.mock(SyncExecutor.class), factory, new TicketIdWriter(), new DirectorySync());
		int exit = runner.run(config.toString(), null, xlsx.toString(), true, tempDir.resolve("logs").toString(),
				false, false, null);

		assertThat(exit).isEqualTo(1);
		Mockito.verifyNoInteractions(factory);
	}
}
