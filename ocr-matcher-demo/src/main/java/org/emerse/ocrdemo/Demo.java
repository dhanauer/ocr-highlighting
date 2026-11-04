package org.emerse.ocrdemo;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs {@link OcrMatcher#highlight} over every example in a directory.
 *
 * For each {@code NAME.html} (OCR spans overlaid on {@code NAME.png}) it reads
 * {@code NAME.query.txt} (the LLM-extracted quote), writes {@code NAME.highlighted.html}, and
 * prints which word spans got wrapped in {@code <mark>}.
 *
 * Usage: java -cp ... org.emerse.ocrdemo.Demo [examplesDir]
 */
public final class Demo {

    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(args.length > 0 ? args[0] : "examples");
        List<Path> inputs;
        try (Stream<Path> s = Files.list(dir)) {
            inputs = s.filter(p -> p.getFileName().toString().endsWith(".html"))
                      .filter(p -> !p.getFileName().toString().endsWith(".highlighted.html"))
                      .sorted().collect(Collectors.toList());
        }

        for (Path html : inputs) {
            String name = html.getFileName().toString().replaceAll("\\.html$", "");
            String ocrHtml = Files.readString(html, StandardCharsets.UTF_8);
            String query = Files.readString(dir.resolve(name + ".query.txt")).trim();

            long t0 = System.nanoTime();
            String result = OcrMatcher.highlight(ocrHtml, query);
            double ms = (System.nanoTime() - t0) / 1e6;

            System.out.println("=== " + name + " ===");
            System.out.println("query      : " + query);
            if (result == null) {
                System.out.printf("highlighted: NO MATCH (%.0f ms)%n%n", ms);
                continue;
            }
            Path out = dir.resolve(name + ".highlighted.html");
            Files.writeString(out, result, StandardCharsets.UTF_8);

            Document doc = Jsoup.parse(result);
            List<String> marked = new ArrayList<>();
            for (Element span : doc.select("span.ocrw")) {
                if (!span.select("mark").isEmpty()) marked.add(span.text());
            }
            System.out.printf("highlighted: %s%n", String.join(" ", marked));
            System.out.printf("             (%d span(s), %.0f ms) -> %s%n%n", marked.size(), ms, out);
        }
    }
}
