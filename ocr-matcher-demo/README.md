# OcrMatcher demo

A runnable demonstration of `OcrMatcher`, which finds an LLM-extracted quote inside OCR'd HTML
(one `<span class="ocrw">` per word) and wraps the matching words in `<mark>`.

`OcrMatcher.java` is unmodified apart from the added `package` line.

All examples are **synthetic**: the page images are rendered from scratch (no real patient data) and
contain **no highlighting**. Each example page is the scan plus an invisible text layer: one
absolutely-positioned `<span class="ocrw">` per word, at the word's coordinates on the image, in the
order the (simulated) OCR engine emitted the words. Highlighting is added only by the matcher.

## Examples (`examples/`)

| Case | OCR span order | Result |
|---|---|---|
| `echo_two_column` | Two-column echo report; spans interleaved line by line across the columns, so the sentence is scrambled in span order (Figure 1; includes the "Left" -> "Leit" misread) | Quote highlighted correctly |
| `lab_table` | Lab table; spans emitted as the comment paragraph first, then the table column by column (labels, values, ranges, flags) | **Known failure (Figure 2):** highlights the `BUN/Creatinine` label at the bottom rather than the `Creatinine` label on the matched row, plus the `H` flag of the BUN row |

The span order in `lab_table.html` was chosen so that the unmodified matcher reproduces the failure
shown in Figure 2; a real OCR engine's order for the real document is not available here. The matcher
scores candidates by distance in span order (`sequenceIndex`), not in pixels.

Files per example: `NAME.png` (scan, unhighlighted), `NAME.html` (OCR spans over the scan, matcher
input), `NAME.query.txt` (the LLM quote), and generated `NAME.highlighted.html` / `.png` (matcher output).

## Run

```
mvn -q compile exec:java        # builds, runs Demo over ./examples, rewrites *.highlighted.html
```

or without Maven (jsoup jar on the classpath):

```
javac -cp jsoup.jar -d out src/main/java/org/emerse/ocrdemo/*.java
java  -cp jsoup.jar:out org.emerse.ocrdemo.Demo examples
```

Open any `examples/*.highlighted.html` in a browser. To regenerate the synthetic inputs:

```
pip install pillow
python3 tools/make_examples.py
```

## Expected console output

```
=== echo_two_column ===
highlighted: Structurally normal tricuspid valve. There is no tricuspid stenosis. There is mild tricuspid regurgitation.
=== lab_table ===
highlighted: BUN/Creatinine 1.29 0.70 1.30 mg/dL H
```
