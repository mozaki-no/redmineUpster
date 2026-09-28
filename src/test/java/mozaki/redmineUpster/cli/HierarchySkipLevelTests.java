package mozaki.redmineUpster.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mozaki.redmineUpster.config.SyncConfigProperties.ColumnsConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.ProjectConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.RedmineConfig;
import mozaki.redmineUpster.config.SyncConfigProperties.SyncConfig;
import mozaki.redmineUpster.service.SpreadsheetParser;
import mozaki.redmineUpster.service.SpreadsheetParser.ParsedSheet;

/**
 * 階層を飛ばした行（大分類・中分類の直下のタスクなど）の親子判定を、ファイル解析から通しで確認します。
 */
class HierarchySkipLevelTests {

	private static final List<String> HIERARCHY = List.of("大分類", "中分類", "小分類", "成果物", "タスク");
	private static final String[] HEADERS = { "チケットID", "トラッカー", "大分類", "中分類", "小分類", "成果物", "タスク" };

	/** P: 大分類=A / Q: A>M / R: A>M>(飛ばし)>T1 / S: A>(飛ばし)>T2 */
	private static final String[][] PQRS = {
			{ "", "サマリ", "A", "", "", "", "" },
			{ "", "サマリ", "A", "M", "", "", "" },
			{ "", "タスク", "A", "M", "", "", "T1" },
			{ "", "タスク", "A", "", "", "", "T2" },
	};

	private final SpreadsheetParser parser = new SpreadsheetParser();
	private final DiffCalculator calculator = new DiffCalculator();

	private static ProjectConfig config(boolean fillDown) {
		ColumnsConfig columns = new ColumnsConfig();
		columns.setHierarchy(HIERARCHY);
		columns.setFillDownHierarchy(fillDown);
		SyncConfig sync = new SyncConfig();
		sync.setColumns(columns);
		sync.setTrackerMap(Map.of("タスク", "2", "サマリ", "6"));
		RedmineConfig redmine = new RedmineConfig();
		redmine.setProjectId("proj");
		ProjectConfig project = new ProjectConfig();
		project.setSync(sync);
		project.setRedmine(redmine);
		return project;
	}

	private DiffPlan plan(Path file, boolean fillDown) throws Exception {
		ProjectConfig config = config(fillDown);
		ParsedSheet sheet = parser.parseFromPath(file.toString(), HIERARCHY, fillDown);
		return calculator.calculate(sheet, config, new TrackerResolver(config.getSync().getTrackerMap(), null), null);
	}

	private static Path writeCsv(Path dir, String[][] rows) throws Exception {
		StringBuilder sb = new StringBuilder(String.join(",", HEADERS)).append('\n');
		for (String[] row : rows) {
			sb.append(String.join(",", row)).append('\n');
		}
		Path file = dir.resolve("wbs.csv");
		Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
		return file;
	}

	private static Path writeXlsx(Path dir, String[][] rows, CellRangeAddress... merges) throws Exception {
		Path file = dir.resolve("wbs.xlsx");
		try (Workbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
			Sheet sheet = workbook.createSheet("WBS");
			Row header = sheet.createRow(0);
			for (int c = 0; c < HEADERS.length; c++) {
				header.createCell(c).setCellValue(HEADERS[c]);
			}
			for (int r = 0; r < rows.length; r++) {
				Row row = sheet.createRow(r + 1);
				for (int c = 0; c < rows[r].length; c++) {
					if (!rows[r][c].isEmpty()) {
						row.createCell(c).setCellValue(rows[r][c]);
					}
				}
			}
			for (CellRangeAddress merge : merges) {
				sheet.addMergedRegion(merge);
			}
			workbook.write(out);
		}
		return file;
	}

	private static DiffItem byRow(DiffPlan plan, int rowNumber) {
		return plan.items().stream().filter(i -> i.rowNumber() == rowNumber).findFirst().orElseThrow();
	}

	private static void assertPqrs(DiffPlan plan) {
		assertThat(plan.errors()).isEmpty();
		assertThat(plan.items()).hasSize(4);
		assertThat(byRow(plan, 2).parentRowNumber()).isNull();
		assertThat(byRow(plan, 3).parentRowNumber()).isEqualTo(2);
		assertThat(byRow(plan, 4).parentRowNumber()).isEqualTo(3);
		assertThat(byRow(plan, 4).subject()).isEqualTo("T1");
		assertThat(byRow(plan, 5).parentRowNumber()).isEqualTo(2);
		assertThat(byRow(plan, 5).subject()).isEqualTo("T2");
		assertThat(byRow(plan, 5).levelPath()).isEqualTo("A > T2");
		// 親→子の順に並ぶ
		List<Integer> order = plan.items().stream().map(DiffItem::rowNumber).toList();
		assertThat(order.indexOf(2)).isLessThan(order.indexOf(3)).isLessThan(order.indexOf(4));
		assertThat(order.indexOf(2)).isLessThan(order.indexOf(5));
	}

	@Test
	@DisplayName("CSV: 大分類・中分類の直下のタスク（階層を飛ばした行）の親を判定できる")
	void csv_skippedLevels(@TempDir Path dir) throws Exception {
		assertPqrs(plan(writeCsv(dir, PQRS), false));
	}

	@Test
	@DisplayName("xlsx: 大分類・中分類の直下のタスク（階層を飛ばした行）の親を判定できる")
	void xlsx_skippedLevels(@TempDir Path dir) throws Exception {
		assertPqrs(plan(writeXlsx(dir, PQRS), false));
	}

	@Test
	@DisplayName("xlsx: 縦にセル結合した大分類は各行の値として読み、横結合は階層の飛ばしとして扱う")
	void xlsx_mergedCells(@TempDir Path dir) throws Exception {
		String[][] rows = {
				{ "", "サマリ", "A", "", "", "", "" },
				{ "", "サマリ", "", "M", "", "", "" },
				{ "", "タスク", "", "M", "", "", "T1" },
				{ "", "タスク", "", "", "", "", "T2" },
				{ "", "サマリ", "B", "", "", "", "" },
				{ "", "タスク", "", "N", "", "", "" },
		};
		// 大分類 A を行2〜5、B を行6〜7 に縦結合。行3の中分類〜成果物を横結合（右側は空欄のまま）
		Path file = writeXlsx(dir, rows,
				new CellRangeAddress(1, 4, 2, 2),
				new CellRangeAddress(5, 6, 2, 2),
				new CellRangeAddress(2, 2, 3, 5));
		// Q の行の中分類は行4と結合しないため、行4には M を明示している
		DiffPlan plan = plan(file, false);
		assertPqrs(new DiffPlan(plan.items().stream().filter(i -> i.rowNumber() <= 5).toList(), plan.errors()));
		assertThat(byRow(plan, 7).parentRowNumber()).isEqualTo(6);
		assertThat(byRow(plan, 7).levelPath()).isEqualTo("B > N");
	}

	@Test
	@DisplayName("xlsx: セル結合の末尾だけが残った空行は読み飛ばす")
	void xlsx_mergedTailOnlyRowIsIgnored(@TempDir Path dir) throws Exception {
		String[][] rows = {
				{ "", "サマリ", "A", "", "", "", "" },
				{ "", "タスク", "", "", "", "", "T2" },
				{ "", "", "", "", "", "", "" },
		};
		Path file = writeXlsx(dir, rows, new CellRangeAddress(1, 3, 2, 2));
		ParsedSheet sheet = parser.parseFromPath(file.toString(), HIERARCHY, false);
		assertThat(sheet.rowNumbers()).containsExactly(2, 3);
		assertThat(sheet.rows().get(1).get("大分類")).isEqualTo("A");
	}

	@Test
	@DisplayName("階層を飛ばした行の祖先（大分類の行）がない場合は親行なしのエラー")
	void skippedLevelWithMissingAncestorIsError(@TempDir Path dir) throws Exception {
		String[][] rows = {
				{ "", "サマリ", "A", "M", "", "", "" },
				{ "", "タスク", "A", "", "", "", "T2" },
		};
		DiffPlan plan = plan(writeCsv(dir, rows), false);
		assertThat(plan.errors()).anySatisfy(e -> assertThat(e).contains("行3").contains("親行 [A]"));
		assertThat(plan.errors()).anySatisfy(e -> assertThat(e).contains("行2").contains("親行 [A]"));
	}

	@Test
	@DisplayName("fillDownHierarchy: true なら旧来どおり前行の値で補完する（階層の飛ばしは不可）")
	void fillDownRestoresOldBehaviour(@TempDir Path dir) throws Exception {
		String[][] rows = {
				{ "", "サマリ", "A", "", "", "", "" },
				{ "", "サマリ", "", "M", "", "", "" },
				{ "", "タスク", "", "", "", "", "T1" },
				{ "", "タスク", "", "", "", "", "T2" },
		};
		Path file = writeCsv(dir, rows);
		DiffPlan plan = plan(file, true);
		assertThat(plan.errors()).isEmpty();
		assertThat(byRow(plan, 3).levelPath()).isEqualTo("A > M");
		assertThat(byRow(plan, 4).levelPath()).isEqualTo("A > M > T1");
		assertThat(byRow(plan, 4).parentRowNumber()).isEqualTo(3);
		assertThat(byRow(plan, 5).levelPath()).isEqualTo("A > M > T2");
		assertThat(byRow(plan, 5).parentRowNumber()).isEqualTo(3);

		// 既定（補完なし）では空欄は補完されず、大分類が空欄の行はエラーになる（最上位として作成しない）
		DiffPlan noFill = plan(file, false);
		assertThat(noFill.errors()).hasSize(3)
				.allSatisfy(e -> assertThat(e).contains("大分類が空欄").contains("fillDownHierarchy"));
	}

	@Test
	@DisplayName("階層を飛ばした行でも階層パスの重複は検出する")
	void duplicatePathWithSkippedLevels(@TempDir Path dir) throws Exception {
		String[][] rows = {
				{ "", "サマリ", "A", "", "", "", "" },
				{ "", "タスク", "A", "", "", "", "T2" },
				{ "", "タスク", "A", "", "", "", "T2" },
				{ "", "タスク", "A", "", "T2", "", "" },
		};
		DiffPlan plan = plan(writeCsv(dir, rows), false);
		assertThat(plan.errors()).anySatisfy(e -> assertThat(e).contains("行4").contains("行3と重複"));
		// 同じ値でも列（階層）が違えば別の行として扱う
		assertThat(byRow(plan, 5).parentRowNumber()).isEqualTo(2);
	}
}
