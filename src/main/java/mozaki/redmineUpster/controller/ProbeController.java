package mozaki.redmineUpster.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import mozaki.redmineUpster.dto.ProbeHeadersResponse;
import mozaki.redmineUpster.service.SpreadsheetParser;
import mozaki.redmineUpster.util.ColumnDefinitions;

@RestController
@RequestMapping(path = "/api/probe-headers", produces = MediaType.APPLICATION_JSON_VALUE)
public class ProbeController {
	private final SpreadsheetParser spreadsheetParser;

	public ProbeController(SpreadsheetParser spreadsheetParser) {
		this.spreadsheetParser = spreadsheetParser;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ProbeHeadersResponse probe(@RequestParam("file") MultipartFile file) throws IOException {
		SpreadsheetParser.ParsedSheet sheet = spreadsheetParser.parse(file);
		List<String> missing = new ArrayList<>();
		for (String required : ColumnDefinitions.REQUIRED_HEADERS) {
			if (!sheet.headers().contains(required)) {
				missing.add(required);
			}
		}
		return new ProbeHeadersResponse(sheet.headers(), missing);
	}
}
