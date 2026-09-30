package mozaki.redmineUpster.samples;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbookType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import mozaki.redmineUpster.cli.DiffCalculator;
import mozaki.redmineUpster.cli.DiffItem;
import mozaki.redmineUpster.cli.DiffPlan;
import mozaki.redmineUpster.cli.TrackerResolver;
import mozaki.redmineUpster.config.SyncConfigProperties;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.service.ExcelSource;
import mozaki.redmineUpster.service.SpreadsheetParser;
import mozaki.redmineUpster.service.SpreadsheetParser.ParsedSheet;
import mozaki.redmineUpster.service.SyncConfigService;
import mozaki.redmineUpster.service.TicketIdWriter;

/**
 * 配布用サンプル（samples/sample-wbs.xlsx / .xlsm）を配布版の設定（packaging/dist/sync-config.yml）で読めることの確認。
 * <p>
 * サンプルは {@link SampleWorkbookGenerator} で作成します。
 * </p>
 */
class SampleWorkbookTests {

	/** 取込表の行番号 → 期待する親の行番号（null=最上位） */
	private static final Map<Integer, Integer> EXPECTED_PARENTS = new LinkedHashMap<>();
	static {
		EXPECTED_PARENTS.put(4, null); // 要件定義
		EXPECTED_PARENTS.put(5, 4); // 要件定義 > 業務要件
		EXPECTED_PARENTS.put(6, 5); // 業務要件の直下のタスク（小分類・成果物を飛ばす）
		EXPECTED_PARENTS.put(7, 5);
		EXPECTED_PARENTS.put(8, 4); // 大分類の直下のタスク
		EXPECTED_PARENTS.put(9, null); // 基本設計
		EXPECTED_PARENTS.put(10, 9);
		EXPECTED_PARENTS.put(11, 10);
		EXPECTED_PARENTS.put(12, 11);
		EXPECTED_PARENTS.put(13, 12);
		EXPECTED_PARENTS.put(14, 12);
		EXPECTED_PARENTS.put(15, 10); // 中分類の直下のタスク
		EXPECTED_PARENTS.put(16, 9);
		EXPECTED_PARENTS.put(17, 16);
		EXPECTED_PARENTS.put(18, 9); // 大分類の直下のタスク
	}

	@TempDir
	Path dir;

	private final SpreadsheetParser parser = new SpreadsheetParser();
	private final DiffCalculator calculator = new DiffCalculator();

	private static ProjectConfig distConfig() {
		SyncConfigService service = new SyncConfigService(new SyncConfigProperties());
		service.loadConfig(Path.of("packaging/dist/sync-config.yml").toAbsolutePath().toString());
		return service.getDefaultProject().orElseThrow();
	}

	private static DiffItem byRow(DiffPlan plan, int row) {
		return plan.items().stream().filter(i -> i.rowNumber() == row).findFirst().orElseThrow();
	}

	@ParameterizedTest
	@ValueSource(strings = { "samples/sample-wbs.xlsx", "samples/sample-wbs.xlsm" })
	@DisplayName("サンプルを配布版の設定（テーブル「取込表」）で読むと、検証エラーなしで階層の飛ばしを含む親子関係になる")
	void sampleValidatesWithDistributedConfig(String file) throws Exception {
		ProjectConfig config = distConfig();
		ExcelSource source = new ExcelSource(config.getSync().getExcel().getSheet(),
				config.getSync().getExcel().getTable());
		assertThat(source.table()).isEqualTo(SampleWorkbookGenerator.TABLE_NAME);

		ParsedSheet sheet = parser.parseFromPath(file, source);
		assertThat(sheet.sheetName()).isEqualTo(SampleWorkbookGenerator.IMPORT_SHEET);
		assertThat(sheet.rowNumbers()).containsExactlyElementsOf(EXPECTED_PARENTS.keySet());
		// 参照先が空欄の数式は空欄、日付は ISO 形式、進捗率は 50% → 50
		Map<String, String> flow = sheet.rows().get(3);
		assertThat(flow).containsEntry("チケットID", "").containsEntry("タスク", "業務フロー作成")
				.containsEntry("小分類", "").containsEntry("着手予定", "2026-10-06").containsEntry("完了予定", "2026-10-14")
				.containsEntry("ステータス", "進行中").containsEntry("進捗率", "50");
		// 結合セル（大分類・中分類）は結合範囲の先頭セルを参照している
		assertThat(sheet.rows().get(11)).containsEntry("大分類", "基本設計").containsEntry("中分類", "画面設計")
				.containsEntry("小分類", "").containsEntry("タスク", "画面一覧作成");
		assertThat(sheet.rows().get(13)).containsEntry("大分類", "基本設計").containsEntry("中分類", "DB設計")
				.containsEntry("タスク", "テーブル定義作成");

		DiffPlan plan = calculator.calculate(sheet, config,
				new TrackerResolver(config.getSync().getTrackerMap(), null), null);
		assertThat(plan.errors()).isEmpty();
		assertThat(plan.items()).hasSize(EXPECTED_PARENTS.size()).allMatch(i -> "CREATE".equals(i.action()));
		EXPECTED_PARENTS.forEach((row, parent) -> assertThat(byRow(plan, row).parentRowNumber())
				.as("行%d の親", row).isEqualTo(parent));
		assertThat(byRow(plan, 8).subject()).isEqualTo("要件定義書レビュー");
		assertThat(byRow(plan, 15).subject()).isEqualTo("画面一覧作成");
		assertThat(byRow(plan, 4).trackerId()).isEqualTo(6L);
		assertThat(byRow(plan, 6).trackerId()).isEqualTo(2L);
		assertThat(byRow(plan, 7).payload()).containsEntry("progress", 50);
	}

	@Test
	@DisplayName("xlsm: 取込表の参照をたどって WBS シートへ書き戻し、xlsm のまま保存して .bak を残す")
	void xlsmWriteBackKeepsTypeAndWritesThroughReferences() throws Exception {
		Path file = dir.resolve("sample-wbs.xlsm");
		Files.copy(Path.of("samples/sample-wbs.xlsm"), file);
		ExcelSource source = new ExcelSource(null, SampleWorkbookGenerator.TABLE_NAME);

		TicketIdWriter.WriteBackResult result = new TicketIdWriter().writeBack(file.toString(), "チケットID",
				Map.of(4, 101L, 8, 105L), source);

		assertThat(result.failures()).isEmpty();
		assertThat(result.backup()).isEqualTo(dir.resolve("sample-wbs.xlsm.bak"));
		assertThat(result.backup()).exists();
		assertThat(result.notes()).hasSize(2).anyMatch(n -> n.contains("'WBS'!B5"))
				.anyMatch(n -> n.contains("'WBS'!B9"));
		try (InputStream in = Files.newInputStream(file); Workbook wb = WorkbookFactory.create(in)) {
			assertThat(((XSSFWorkbook) wb).getWorkbookType()).isEqualTo(XSSFWorkbookType.XLSM);
			Sheet wbs = wb.getSheet(SampleWorkbookGenerator.WBS_SHEET);
			assertThat(wbs.getRow(4).getCell(1).getNumericCellValue()).isEqualTo(101d);
			assertThat(wbs.getRow(8).getCell(1).getNumericCellValue()).isEqualTo(105d);
			// 取込表のセルは数式のまま
			assertThat(wb.getSheet(SampleWorkbookGenerator.IMPORT_SHEET).getRow(3).getCell(0).getCellType())
					.isEqualTo(CellType.FORMULA);
		}
		ParsedSheet reread = parser.parseFromPath(file.toString(), source);
		assertThat(reread.rows().get(0)).containsEntry("チケットID", "101");
		assertThat(reread.rows().get(4)).containsEntry("チケットID", "105");
		assertThat(reread.rows().get(1)).containsEntry("チケットID", "");
	}

	@Test
	@DisplayName("ジェネレータの出力（xlsx/xlsm）は同じ内容で、数式の計算結果が保存されている")
	void generatorProducesCachedValues() throws Exception {
		for (XSSFWorkbookType type : XSSFWorkbookType.values()) {
			Path file = dir.resolve("gen." + type.getExtension());
			SampleWorkbookGenerator.write(file, type);
			try (InputStream in = Files.newInputStream(file); Workbook wb = WorkbookFactory.create(in)) {
				assertThat(((XSSFWorkbook) wb).getWorkbookType()).isEqualTo(type);
				assertThat(wb.getSheet(SampleWorkbookGenerator.IMPORT_SHEET).getRow(3).getCell(2)
						.getCachedFormulaResultType()).isEqualTo(CellType.STRING);
			}
			ParsedSheet sheet = parser.parseFromPath(file.toString(),
					new ExcelSource(null, SampleWorkbookGenerator.TABLE_NAME));
			assertThat(sheet.rows()).isEqualTo(parser.parseFromPath("samples/sample-wbs.xlsx",
					new ExcelSource(null, SampleWorkbookGenerator.TABLE_NAME)).rows());
		}
	}
}
