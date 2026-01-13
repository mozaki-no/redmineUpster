package mozaki.redmineUpster.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import mozaki.redmineUpster.domain.DiffEntity;
import mozaki.redmineUpster.domain.DiffItemEntity;
import mozaki.redmineUpster.dto.DiffItemResponse;
import mozaki.redmineUpster.dto.DiffResponse;
import mozaki.redmineUpster.repository.DiffItemRepository;
import mozaki.redmineUpster.repository.DiffRepository;
import mozaki.redmineUpster.service.ConfigService;
import mozaki.redmineUpster.service.DiffService;
import mozaki.redmineUpster.service.SpreadsheetParser;
import mozaki.redmineUpster.util.ColumnDefinitions;

@RestController
@RequestMapping(path = "/api/diffs", produces = MediaType.APPLICATION_JSON_VALUE)
public class DiffController {
	private final DiffRepository diffRepository;
	private final DiffItemRepository diffItemRepository;
	private final SpreadsheetParser spreadsheetParser;
	private final DiffService diffService;
	private final ConfigService configService;

	public DiffController(DiffRepository diffRepository, DiffItemRepository diffItemRepository,
			SpreadsheetParser spreadsheetParser, DiffService diffService, ConfigService configService) {
		this.diffRepository = diffRepository;
		this.diffItemRepository = diffItemRepository;
		this.spreadsheetParser = spreadsheetParser;
		this.diffService = diffService;
		this.configService = configService;
	}

	@GetMapping
	public List<DiffResponse> list() {
		return diffRepository.findAll().stream()
				.map(diff -> new DiffResponse(diff.getId(), diff.getFilename(), diff.getCreatedAt(),
						(int) diffItemRepository.countByDiffId(diff.getId())))
				.toList();
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public DiffResponse create(@RequestParam("file") MultipartFile file) throws IOException {
		SpreadsheetParser.ParsedSheet sheet = spreadsheetParser.parse(file);
		validateRequiredHeaders(sheet.headers());
		DiffEntity diff = diffService.createDiff(file.getOriginalFilename(), sheet.rows(), configService.getConfigMap());
		int count = (int) diffItemRepository.countByDiffId(diff.getId());
		return new DiffResponse(diff.getId(), diff.getFilename(), diff.getCreatedAt(), count);
	}

	@GetMapping("/{diffId}/items")
	public List<DiffItemResponse> items(@PathVariable("diffId") Long diffId) {
		List<DiffItemEntity> items = diffItemRepository.findByDiffIdOrderById(diffId);
		return items.stream()
				.map(item -> new DiffItemResponse(item.getId(), item.getExternalKey(), item.getSubject(),
						item.getParentKey(), item.getLevelPath(), item.getAction(), item.getStatus()))
				.toList();
	}

	private void validateRequiredHeaders(List<String> headers) {
		for (String required : ColumnDefinitions.REQUIRED_HEADERS) {
			if (!headers.contains(required)) {
				throw new IllegalArgumentException("missing required header: " + required);
			}
		}
	}
}
