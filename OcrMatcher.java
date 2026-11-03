import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Standalone, extraction of the OCR matching path from
 * {@code org.emerse.aiintegration.matcher}. Given OCR'd HTML (words wrapped in
 * {@code <span style="..."> } elements, one per detected word/line fragment) and a
 * search phrase, finds the best-matching run of words and wraps them in {@code <mark>}.
 *
 *
 * <h2>Algorithm overview</h2>
 * <ol>
 *   <li><b>Parse</b>: pull OCR word-spans out of the HTML in document order and tokenize
 *       each one into lowercase, diacritic-folded tokens (one {@link Word} per token).</li>
 *   <li><b>Anchor selection</b>: pick 2-5 rare, distinctive query tokens to seed the search
 *       from, rather than starting from every query token (which would be O(n²) on noisy
 *       OCR text with lots of repeated common words).</li>
 *   <li><b>Clustering</b>: group each anchor's document occurrences into windows of nearby
 *       {@code sequenceIndex} so unrelated, far-apart occurrences of the same rare word
 *       don't get pulled into one search space.</li>
 *   <li><b>Subgraph extraction</b>: around each cluster, carve out a bounded window of
 *       candidate words (closest-to-anchor first) so the search below only ever looks at
 *       a few hundred words instead of the whole document.</li>
 *   <li><b>Pass 1 - sequential bidirectional search</b>: OCR text is noisy but still
 *       60-70% "reading order" (Jsoup gives us spans in roughly left-to-right,
 *       top-to-bottom order). So Pass 1 treats it like elastic HTML matching: walk
 *       forward and backward from each anchor, tolerating small gaps and a lookahead
 *       window for skipped/misread words, scoring tight consecutive runs higher.</li>
 *   <li><b>Pass 2 - index-proximity fallback</b>: only runs when Pass 1's match ratio is
 *       below {@link #TWO_PASS_QUALITY_THRESHOLD}. Ignores clustering entirely and
 *       searches the whole document: anchor on the single rarest query token, then for
 *       every query position greedily pick the nearest-by-index unused candidate to its
 *       already-placed neighbor. Cheaper per-anchor-occurrence than Pass 1 but it tries
 *       every occurrence of the rarest token globally, which is what rescues matches that
 *       landed in the "wrong" cluster during Pass 1.</li>
 *   <li><b>Render</b>: wrap each span element that contributed a matched word in
 *       {@code <mark>}.</li>
 * </ol>
 */
public final class OcrMatcher {

    private OcrMatcher() {
    }

    // ------------------------------------------------------------------
    // Tunable thresholds (mirrors MatcherConfig's OCR + index-proximity knobs)
    // ------------------------------------------------------------------

    /** OCR text is noisy; accept a match once this fraction of query tokens is covered. */
    private static final double OCR_COMPLETION_THRESHOLD = 0.70;
    /** A match below this fraction of matched query tokens is discarded outright. */
    private static final double OCR_MIN_TOKENS_RATIO = 0.60;
    /** Pass 1 match ratio at/above which Pass 2 (more expensive, global) is skipped. */
    private static final double TWO_PASS_QUALITY_THRESHOLD = 0.90;
    /** Pass 2 accepts a match only if it covers at least this fraction of query tokens. */
    private static final double INDEX_PROXIMITY_MATCH_THRESHOLD = 0.70;
    /** Local-optimization sweeps Pass 2 runs trying to swap in better-fitting candidates. */
    private static final int INDEX_PROXIMITY_REFINEMENT_PASSES = 3;

    /** How many query tokens ahead Pass 1 is willing to look when the exact-next token is missing. */
    private static final int LOOKAHEAD_WINDOW = 3;
    /** Of those lookahead candidates, how many are actually explored per step (cost control). */
    private static final int LOOKAHEAD_TOP_K = 2;
    /** Max document-index distance Pass 1 will jump between two matched words. */
    private static final int MAX_SEQUENCE_DISTANCE = 50;
    /** Max iterations of the Pass-1 search loop per anchor occurrence (safety valve). */
    private static final int MAX_ITERATIONS = 5000;
    /** Beam width: how many in-flight search states Pass 1 keeps per forward query position. */
    private static final int BEAM_WIDTH_CAP = 200;
    private static final int BEAM_WIDTH_PER_QUERY_TOKEN = 4;

    // ==================================================================
    // Public API
    // ==================================================================

    /**
     * @param ocrHtml      OCR'd HTML: word spans look like
     *                     {@code <span class="ocrw" style="left:10px;top:20px;width:30px;height:12px">word</span>}
     * @param searchPhrase the text to find (already plain text, not HTML)
     * @return the input HTML with the best-matching run of words wrapped in
     *         {@code <mark>}, or {@code null} if nothing matched well enough
     */
    public static String highlight(String ocrHtml, String searchPhrase) {
        List<Word> words = parseOcrHtml(ocrHtml);
        if (words.isEmpty()) return null;

        List<String> queryTokens = tokenize(searchPhrase);
        if (queryTokens.isEmpty()) return null;

        Map<String, List<Word>> globalIndex = buildTokenIndex(words);

        MatchResult result = searchTwoPass(queryTokens, words, globalIndex);
        if (result == null) return null;

        return renderHighlights(result.path, words);
    }

    // ==================================================================
    // Data model
    // ==================================================================

    /** One token of one OCR word, positioned in document reading order. */
    static final class Word {
        final String token;          // normalized token text, e.g. "patient"
        final int sequenceIndex;     // 0-based position among all parsed tokens
        final Element span;          // the OCR span this token came from (for rendering)
        final int x, y;              // pixel position, kept for reference / future use only

        Word(String token, int sequenceIndex, Element span, int x, int y) {
            this.token = token;
            this.sequenceIndex = sequenceIndex;
            this.span = span;
            this.x = x;
            this.y = y;
        }
    }

    /** A candidate query token picked to seed the search, plus where it occurs in the document. */
    private static final class Anchor {
        final String token;
        final int queryPosition;
        final double score;
        final List<Word> occurrences;

        Anchor(String token, int queryPosition, double score, List<Word> occurrences) {
            this.token = token;
            this.queryPosition = queryPosition;
            this.score = score;
            this.occurrences = occurrences;
        }
    }

    /** The outcome of matching the query against one region of the document. */
    private static final class MatchResult {
        final List<Word> path;     // matched words, in document order
        final double score;
        final int matchedTokens;   // how many DISTINCT query tokens this path covers

        MatchResult(List<Word> path, double score, int matchedTokens) {
            this.path = path;
            this.score = score;
            this.matchedTokens = matchedTokens;
        }
    }

    // ==================================================================
    // Step 1: Parse OCR HTML into sequential Words
    // ==================================================================

    private static final Pattern STYLE_NUMBER = Pattern.compile("(left|top)\\s*:\\s*(\\d+)");

    /**
     * Pulls OCR word-spans out of the document in HTML (= reading) order and tokenizes
     * each one. One {@link Word} is created per TOKEN, not per span: a span like
     * "555-1234" tokenizes to ["555","1234"], and highlighting still only needs the
     * owning span, so there is no cost to the finer granularity and it keeps every
     * downstream step (indexing, scoring, path-building) operating on single tokens.
     */
    private static List<Word> parseOcrHtml(String html) {
        Document doc = Jsoup.parse(html);
        List<Word> words = new ArrayList<>();
        int sequenceIndex = 0;

        for (Element span : doc.select("span[style].ocrw")) {
            String text = span.text().trim();
            if (text.isEmpty()) continue;

            String style = span.attr("style");
            int x = extractInt(style, "left");
            int y = extractInt(style, "top");

            for (String word : text.split("\\s+")) {
                for (String token : tokenize(word)) {
                    words.add(new Word(token, sequenceIndex++, span, x, y));
                }
            }
        }
        return words;
    }

    private static int extractInt(String style, String property) {
        Matcher m = Pattern.compile(property + "\\s*:\\s*(\\d+)").matcher(style);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // ==================================================================
    // Step 2: Tokenizer
    // ==================================================================

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    // Unicode-aware on purpose: an ASCII-only class would shred non-Latin OCR text.
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}\\s]");

    /**
     * Lowercases, strips punctuation, and folds diacritics (NFD-decompose then drop the
     * combining marks) so "Muñoz" in the document and "Munoz" in the search phrase land
     * on the same token regardless of which side has the accent.
     */
    private static List<String> tokenize(String text) {
        String folded = COMBINING_MARKS.matcher(
                Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        String cleaned = NON_ALPHANUMERIC.matcher(folded).replaceAll(" ");
        return Arrays.stream(cleaned.toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());
    }

    // ==================================================================
    // Step 3: Inverted token index
    // ==================================================================

    private static Map<String, List<Word>> buildTokenIndex(List<Word> words) {
        Map<String, List<Word>> index = new HashMap<>();
        for (Word w : words) {
            index.computeIfAbsent(w.token, k -> new ArrayList<>()).add(w);
        }
        return index;
    }

    // ==================================================================
    // Step 4: Anchor selection
    // ==================================================================

    /**
     * Picks 2-5 query tokens to start searches from, favoring tokens that are RARE in the
     * document (so each occurrence is a strong signal of location) and spread across the
     * query (so a bad guess at one anchor doesn't sink the whole search). Starting a
     * search from every query token instead would multiply the cost of every later step
     * by the query length for no benefit — common tokens like "the" occur everywhere and
     * tell the search nothing about where the real match is.
     */
    private static List<Anchor> selectAnchors(List<String> queryTokens, Map<String, List<Word>> index) {
        List<Anchor> candidates = new ArrayList<>();
        for (int i = 0; i < queryTokens.size(); i++) {
            String token = queryTokens.get(i);
            List<Word> occurrences = index.getOrDefault(token, Collections.emptyList());
            if (occurrences.isEmpty()) continue;
            candidates.add(new Anchor(token, i, anchorScore(occurrences.size(), token), occurrences));
        }

        candidates.sort((a, b) -> Double.compare(b.score, a.score));

        int targetCount = queryTokens.size() < 10 ? 2 : queryTokens.size() < 30 ? 3 : 5;
        List<Anchor> selected = new ArrayList<>();
        for (Anchor candidate : candidates) {
            if (selected.size() >= targetCount) break;
            // A low-scoring token (very common elsewhere) is only ever acceptable as the
            // FIRST anchor (the search needs at least one to run); never pad out with more.
            if (!selected.isEmpty() && candidate.score < 0.0) continue;

            boolean tooClose = selected.stream()
                    .anyMatch(existing -> Math.abs(candidate.queryPosition - existing.queryPosition) < 3);
            if (!tooClose) selected.add(candidate);
        }

        selected.sort(Comparator.comparingInt(a -> a.queryPosition));
        return selected;
    }

    /** Higher = rarer/more distinctive = better anchor. */
    private static double anchorScore(int occurrenceCount, String token) {
        double score = 100.0 / occurrenceCount;           // rarity bonus
        score += Math.min(20, token.length() * 2);        // longer tokens are more distinctive
        if (token.matches(".*\\d.*")) score += 15;         // numbers (dates, doses) are distinctive
        if (occurrenceCount > 50) score -= 30;              // heavily common token, penalize
        return score;
    }

    // ==================================================================
    // Step 5 + 6: Sequential clustering and subgraph extraction
    // ==================================================================

    /**
     * Groups all anchors' document occurrences into clusters by proximity in reading
     * order: a new cluster starts whenever the gap to the previous occurrence exceeds
     * {@code windowSize}. This keeps two occurrences of the same rare word that are
     * pages apart from being treated as one search region.
     */
    private static List<List<Word>> clusterAnchorOccurrences(List<Anchor> anchors, int windowSize) {
        List<Word> all = new ArrayList<>();
        for (Anchor a : anchors) all.addAll(a.occurrences);
        all.sort(Comparator.comparingInt(w -> w.sequenceIndex));

        List<List<Word>> clusters = new ArrayList<>();
        List<Word> current = null;
        int lastMax = Integer.MIN_VALUE;
        for (Word w : all) {
            if (current == null || w.sequenceIndex - lastMax > windowSize) {
                current = new ArrayList<>();
                clusters.add(current);
            }
            current.add(w);
            lastMax = Math.max(lastMax, w.sequenceIndex);
        }
        return clusters;
    }

    /**
     * Carves a bounded window of candidate words out of the full document around one
     * cluster, ordered by proximity to the nearest anchor occurrence (closest first).
     * Ordering matters: when a candidate list is later capped at {@code maxNodes}, the
     * words nearest the anchors must survive the cut, not whichever happened to come
     * first in document order.
     */
    private static List<Word> extractSubgraph(List<Word> clusterAnchors, List<Word> allWords,
                                                int maxNodes, int queryLength) {
        int minAnchorSeq = clusterAnchors.stream().mapToInt(w -> w.sequenceIndex).min().orElse(0);
        int maxAnchorSeq = clusterAnchors.stream().mapToInt(w -> w.sequenceIndex).max().orElse(0);

        int expansion = Math.max(queryLength, 30);
        int buffer = Math.max(20, queryLength / 2);
        int lowBound = Math.max(0, minAnchorSeq - expansion - buffer);
        int highBound = Math.min(allWords.size() - 1, maxAnchorSeq + expansion + buffer);

        List<Word> inRange = new ArrayList<>();
        for (Word w : allWords) {
            if (w.sequenceIndex >= lowBound && w.sequenceIndex <= highBound) inRange.add(w);
        }

        inRange.sort(Comparator.comparingInt(w ->
                clusterAnchors.stream().mapToInt(a -> Math.abs(w.sequenceIndex - a.sequenceIndex)).min().orElse(Integer.MAX_VALUE)));

        LinkedHashSet<Word> subgraph = new LinkedHashSet<>(clusterAnchors);
        for (Word w : inRange) {
            if (subgraph.size() >= maxNodes) break;
            subgraph.add(w);
        }

        List<Word> ordered = new ArrayList<>(subgraph);
        ordered.sort(Comparator.comparingInt(w -> w.sequenceIndex));
        return ordered;
    }

    // ==================================================================
    // Two-pass coordination
    // ==================================================================

    /**
     * Runs Pass 1 (cheap, cluster-scoped, reading-order search) first; only falls through
     * to Pass 2 (expensive, whole-document, order-agnostic-ish search) when Pass 1 either
     * found nothing or its coverage of the query fell below {@link #TWO_PASS_QUALITY_THRESHOLD}.
     */
    private static MatchResult searchTwoPass(List<String> queryTokens, List<Word> allWords,
                                              Map<String, List<Word>> globalIndex) {
        MatchResult pass1 = sequentialSearch(queryTokens, allWords, globalIndex);

        if (pass1 != null && (double) pass1.matchedTokens / queryTokens.size() >= TWO_PASS_QUALITY_THRESHOLD) {
            return pass1;
        }

        MatchResult pass2 = indexProximitySearch(queryTokens, allWords);

        if (pass1 == null) return pass2;
        if (pass2 == null) return pass1;
        return compareMatches(pass1, pass2) <= 0 ? pass1 : pass2;
    }

    /** More matched tokens wins; on a tie, higher score wins. Negative means r1 is better. */
    private static int compareMatches(MatchResult r1, MatchResult r2) {
        if (r1.matchedTokens != r2.matchedTokens) return Integer.compare(r2.matchedTokens, r1.matchedTokens);
        return Double.compare(r2.score, r1.score);
    }

    // ==================================================================
    // Pass 1: Sequential bidirectional search (elastic, reading-order)
    // ==================================================================

    /**
     * For every anchor occurrence, in every cluster, walks forward and backward through
     * the query, extending the match word by word. Returns the best result across all
     * anchors/clusters that clears {@link #OCR_MIN_TOKENS_RATIO}.
     */
    private static MatchResult sequentialSearch(List<String> queryTokens, List<Word> allWords,
                                                 Map<String, List<Word>> globalIndex) {
        List<Anchor> anchors = selectAnchors(queryTokens, globalIndex);
        if (anchors.isEmpty()) return null;

        int windowSize = Math.max(20, queryTokens.size() * 2);
        int maxNodes = windowSize * 2;
        List<List<Word>> clusters = clusterAnchorOccurrences(anchors, windowSize);

        List<MatchResult> best = new ArrayList<>();
        for (List<Word> clusterAnchorWords : clusters) {
            List<Word> subgraph = extractSubgraph(clusterAnchorWords, allWords, maxNodes, queryTokens.size());
            Map<String, List<Word>> localIndex = buildTokenIndex(subgraph);

            int beamWidth = Math.min(BEAM_WIDTH_CAP, queryTokens.size() * BEAM_WIDTH_PER_QUERY_TOKEN);

            for (Word anchorWord : clusterAnchorWords) {
                int queryPosition = anchorPositionOf(anchorWord, anchors);
                if (queryPosition < 0) continue;

                MatchResult result = bidirectionalSearchFromAnchor(
                        queryTokens, localIndex, anchorWord, queryPosition, beamWidth);
                if (result != null) best.add(result);
            }
        }

        if (best.isEmpty()) return null;
        best.sort(OcrMatcher::compareMatches);

        MatchResult top = best.get(0);
        int minNeeded = (int) Math.ceil(queryTokens.size() * OCR_MIN_TOKENS_RATIO);
        return top.matchedTokens >= minNeeded ? top : null;
    }

    private static int anchorPositionOf(Word word, List<Anchor> anchors) {
        for (Anchor a : anchors) {
            if (a.occurrences.contains(word)) return a.queryPosition;
        }
        return -1;
    }

    /**
     * Immutable snapshot of one in-progress bidirectional search. {@code forwardIndex} /
     * {@code backwardIndex} are the next query positions still to be matched in each
     * direction; search stops expanding a side once its index runs off the end of the query.
     */
    private static final class SearchState {
        final int forwardIndex, backwardIndex;
        final double score;
        final List<Word> forwardPath, backwardPath;
        final Set<Word> used;
        final int matchedTokens;

        SearchState(int forwardIndex, int backwardIndex, double score,
                    List<Word> forwardPath, List<Word> backwardPath, Set<Word> used, int matchedTokens) {
            this.forwardIndex = forwardIndex;
            this.backwardIndex = backwardIndex;
            this.score = score;
            this.forwardPath = forwardPath;
            this.backwardPath = backwardPath;
            this.used = used;
            this.matchedTokens = matchedTokens;
        }

        boolean isComplete(int queryLength) {
            return matchedTokens >= Math.ceil(queryLength * OCR_COMPLETION_THRESHOLD);
        }

        List<Word> fullPath(Word anchor) {
            List<Word> full = new ArrayList<>();
            for (int i = backwardPath.size() - 1; i >= 0; i--) full.add(backwardPath.get(i));
            full.add(anchor);
            full.addAll(forwardPath);
            return full;
        }
    }

    private static MatchResult bidirectionalSearchFromAnchor(List<String> queryTokens,
                                                               Map<String, List<Word>> localIndex,
                                                               Word anchorWord, int anchorQueryPos,
                                                               int beamWidth) {
        int n = queryTokens.size();
        // Highest score first; ties broken by anchor position for determinism across runs.
        PriorityQueue<SearchState> frontier = new PriorityQueue<>(
                (a, b) -> {
                    int byScore = Double.compare(b.score, a.score);
                    if (byScore != 0) return byScore;
                    return Integer.compare(b.matchedTokens, a.matchedTokens);
                });
        frontier.add(new SearchState(anchorQueryPos + 1, anchorQueryPos - 1, 100.0,
                new ArrayList<>(), new ArrayList<>(), new HashSet<>(List.of(anchorWord)), 1));

        List<SearchState> complete = new ArrayList<>();
        SearchState longestPartial = null;
        int iterations = 0;

        while (!frontier.isEmpty() && iterations++ < MAX_ITERATIONS) {
            SearchState current = frontier.poll();

            if (longestPartial == null || current.matchedTokens > longestPartial.matchedTokens) {
                longestPartial = current;
            }

            boolean bothExhausted = current.forwardIndex >= n && current.backwardIndex < 0;
            if (current.isComplete(n)) {
                complete.add(current);
                if (current.matchedTokens == n && bothExhausted) break; // perfect match, stop early
                if (bothExhausted) continue;                            // nothing left to extend
            }

            if (current.forwardIndex < n) {
                expand(current, queryTokens, localIndex, anchorWord, frontier, true, current.forwardIndex);
            }
            if (current.backwardIndex >= 0) {
                expand(current, queryTokens, localIndex, anchorWord, frontier, false, current.backwardIndex);
            }

            if (iterations % 50 == 0 && frontier.size() > beamWidth) {
                pruneToBeamWidth(frontier, beamWidth);
            }
        }

        if (!complete.isEmpty()) {
            complete.sort((a, b) -> {
                if (a.matchedTokens != b.matchedTokens) return Integer.compare(b.matchedTokens, a.matchedTokens);
                return Double.compare(b.score, a.score);
            });
            SearchState winner = complete.get(0);
            return new MatchResult(winner.fullPath(anchorWord), winner.score, winner.matchedTokens);
        }

        if (longestPartial != null) {
            int minPartial = (int) Math.ceil(n * OCR_MIN_TOKENS_RATIO);
            if (longestPartial.matchedTokens >= minPartial) {
                return new MatchResult(longestPartial.fullPath(anchorWord), longestPartial.score, longestPartial.matchedTokens);
            }
        }
        return null;
    }

    /**
     * Tries to extend {@code current} one more query token in {@code forward} direction.
     * For the target query token, and for a few tokens further ahead (the lookahead
     * window — covers OCR dropping/misreading a word), looks up candidate words and
     * scores each by how close it lands to where it's expected: exactly adjacent scores
     * best, a bigger gap decays the score, and skipping ahead in the query costs more
     * the further it skips. If even the lookahead finds nothing, a zero-cost "soft skip"
     * state is queued so the search can still recover downstream.
     */
    private static void expand(SearchState current, List<String> queryTokens,
                                Map<String, List<Word>> localIndex, Word anchorWord,
                                PriorityQueue<SearchState> frontier, boolean forward, int targetIndex) {

        Word reference = forward
                ? (current.forwardPath.isEmpty() ? anchorWord : current.forwardPath.get(current.forwardPath.size() - 1))
                : (current.backwardPath.isEmpty() ? anchorWord : current.backwardPath.get(current.backwardPath.size() - 1));

        String targetToken = queryTokens.get(targetIndex);
        List<Word> exact = localIndex.getOrDefault(targetToken, Collections.emptyList());

        boolean placedSomething = tryPlaceCandidates(current, exact, targetIndex, reference, forward, frontier);

        if (!placedSomething) {
            // Exact next token absent locally (likely OCR noise/misread) — peek a little
            // further ahead in the query so one bad word doesn't stall the whole match.
            List<int[]> lookaheadOrder = new ArrayList<>(); // [offset, queryIndex]
            for (int offset = 1; offset < LOOKAHEAD_WINDOW; offset++) {
                int idx = forward ? targetIndex + offset : targetIndex - offset;
                if (idx < 0 || idx >= queryTokens.size()) break;
                if (!localIndex.getOrDefault(queryTokens.get(idx), Collections.emptyList()).isEmpty()) {
                    lookaheadOrder.add(new int[]{offset, idx});
                }
            }
            int tried = 0;
            for (int[] pair : lookaheadOrder) {
                if (tried++ >= LOOKAHEAD_TOP_K) break;
                int idx = pair[1];
                List<Word> candidates = localIndex.get(queryTokens.get(idx));
                tryPlaceCandidates(current, candidates, idx, reference, forward, frontier);
            }
            createSoftSkip(current, forward, frontier);
        }
    }

    /** @return true if at least one candidate produced a new frontier state. */
    private static boolean tryPlaceCandidates(SearchState current, List<Word> candidates, int queryIndex,
                                               Word reference, boolean forward,
                                               PriorityQueue<SearchState> frontier) {
        boolean placedAny = false;
        for (Word candidate : candidates) {
            if (current.used.contains(candidate)) continue;

            boolean isAdjacent = Math.abs(candidate.sequenceIndex - reference.sequenceIndex) == 1;
            if (!isAdjacent) {
                boolean directionOk = forward
                        ? candidate.sequenceIndex > reference.sequenceIndex
                        : candidate.sequenceIndex < reference.sequenceIndex;
                if (!directionOk) continue;
                if (Math.abs(candidate.sequenceIndex - reference.sequenceIndex) > MAX_SEQUENCE_DISTANCE) continue;
            }

            int expectedNext = reference.sequenceIndex + (forward ? 1 : -1);
            double transitionScore;
            if (candidate.sequenceIndex == expectedNext) {
                transitionScore = 130.0; // exact reading-order continuation
            } else {
                int gap = Math.abs(candidate.sequenceIndex - expectedNext);
                transitionScore = 100.0 * Math.pow(0.95, gap); // decays with distance
            }

            int skippedQueryTokens = Math.abs(queryIndex - (forward ? current.forwardIndex : current.backwardIndex));
            if (skippedQueryTokens > 0) {
                transitionScore *= Math.pow(0.85, Math.min(skippedQueryTokens, 4));
            }

            List<Word> newForward = new ArrayList<>(current.forwardPath);
            List<Word> newBackward = new ArrayList<>(current.backwardPath);
            (forward ? newForward : newBackward).add(candidate);

            Set<Word> newUsed = new HashSet<>(current.used);
            newUsed.add(candidate);

            frontier.add(new SearchState(
                    forward ? queryIndex + 1 : current.forwardIndex,
                    forward ? current.backwardIndex : queryIndex - 1,
                    current.score + transitionScore,
                    newForward, newBackward, newUsed, current.matchedTokens + 1));
            placedAny = true;
        }
        return placedAny;
    }

    /**
     * Queues a zero-cost state that simply advances past the unmatched query token.
     * Scoring note: the skip costs nothing on top of the score it already has — forfeiting
     * the ~100-130 points a real match would have added IS the penalty. Multiplying the
     * accumulated score instead would make a skip's cost depend on how far into the match
     * it happens, unfairly favoring/penalizing skips by position rather than by count.
     */
    private static void createSoftSkip(SearchState current, boolean forward, PriorityQueue<SearchState> frontier) {
        frontier.add(new SearchState(
                forward ? current.forwardIndex + 1 : current.forwardIndex,
                forward ? current.backwardIndex : current.backwardIndex - 1,
                current.score,
                new ArrayList<>(current.forwardPath), new ArrayList<>(current.backwardPath),
                new HashSet<>(current.used), current.matchedTokens));
    }

    /** Keeps only the top {@code beamWidth} states per forward-query-position, by score. */
    private static void pruneToBeamWidth(PriorityQueue<SearchState> frontier, int beamWidth) {
        Map<Integer, List<SearchState>> byForwardIndex = new HashMap<>();
        for (SearchState s : frontier) byForwardIndex.computeIfAbsent(s.forwardIndex, k -> new ArrayList<>()).add(s);

        frontier.clear();
        for (List<SearchState> group : byForwardIndex.values()) {
            group.sort((a, b) -> Double.compare(b.score, a.score));
            frontier.addAll(group.size() > beamWidth ? group.subList(0, beamWidth) : group);
        }
    }

    // ==================================================================
    // Pass 2: Index-proximity fallback (global, order-agnostic-ish)
    // ==================================================================

    /**
     * Fallback for when Pass 1's cluster-scoped search missed because clustering picked
     * the wrong region (e.g. the right occurrence of a shared anchor word was in a
     * different cluster). Ignores clusters/subgraphs entirely:
     * <ol>
     *   <li>Anchor on the single RAREST query token (fewest document occurrences).</li>
     *   <li>For every occurrence of that token, greedily place every other query token by
     *       walking outward and always taking the nearest-by-{@code sequenceIndex} unused
     *       candidate to the neighbor already placed.</li>
     *   <li>Score each resulting placement by average index-gap between consecutive
     *       matched words, plus a heavy penalty per missing token, and keep the best.</li>
     *   <li>Run a few local-swap passes: for each placed word, see if a different
     *       occurrence of the same token fits its neighbors more tightly.</li>
     * </ol>
     */
    private static MatchResult indexProximitySearch(List<String> queryTokens, List<Word> allWords) {
        Map<String, List<Word>> index = buildTokenIndex(allWords);

        String anchorToken = null;
        int minOccurrences = Integer.MAX_VALUE;
        for (String token : queryTokens) {
            int count = index.getOrDefault(token, Collections.emptyList()).size();
            if (count > 0 && count < minOccurrences) {
                minOccurrences = count;
                anchorToken = token;
            }
        }
        if (anchorToken == null) return null;

        int anchorPosition = queryTokens.indexOf(anchorToken);
        List<MatchResult> candidates = new ArrayList<>();

        for (Word anchorOccurrence : index.get(anchorToken)) {
            Word[] placed = new Word[queryTokens.size()];
            placed[anchorPosition] = anchorOccurrence;
            Set<Integer> usedSeq = new HashSet<>(List.of(anchorOccurrence.sequenceIndex));
            int matchedCount = 1;
            double totalGap = 0;

            for (int i = anchorPosition - 1; i >= 0; i--) {
                Word nearest = nearestUnused(index.get(queryTokens.get(i)), placed[i + 1], usedSeq);
                if (nearest != null) {
                    placed[i] = nearest;
                    usedSeq.add(nearest.sequenceIndex);
                    totalGap += Math.abs(nearest.sequenceIndex - placed[i + 1].sequenceIndex);
                    matchedCount++;
                }
            }
            for (int i = anchorPosition + 1; i < queryTokens.size(); i++) {
                Word nearest = nearestUnused(index.get(queryTokens.get(i)), placed[i - 1], usedSeq);
                if (nearest != null) {
                    placed[i] = nearest;
                    usedSeq.add(nearest.sequenceIndex);
                    totalGap += Math.abs(nearest.sequenceIndex - placed[i - 1].sequenceIndex);
                    matchedCount++;
                }
            }

            double avgGap = matchedCount > 1 ? totalGap / (matchedCount - 1) : 0;
            double missingPenalty = (queryTokens.size() - matchedCount) * 1000.0;
            List<Word> path = Arrays.stream(placed).filter(Objects::nonNull).collect(Collectors.toList());
            if (!path.isEmpty()) {
                candidates.add(new MatchResult(path, avgGap + missingPenalty, matchedCount));
            }
        }

        if (candidates.isEmpty()) return null;
        // Lowest score wins here: score is a distance+penalty cost, not the additive
        // reward used in Pass 1 — tighter index proximity and fewer missing tokens both
        // pull the score DOWN, unlike Pass 1 where a higher score is better.
        candidates.sort(Comparator.comparingDouble(r -> r.score));
        MatchResult best = candidates.get(0);

        if ((double) best.matchedTokens / queryTokens.size() < INDEX_PROXIMITY_MATCH_THRESHOLD) return null;

        return refineByLocalSwaps(best, index);
    }

    private static Word nearestUnused(List<Word> candidates, Word reference, Set<Integer> usedSeq) {
        if (candidates == null || reference == null) return null;
        Word best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Word candidate : candidates) {
            if (usedSeq.contains(candidate.sequenceIndex)) continue;
            int dist = Math.abs(candidate.sequenceIndex - reference.sequenceIndex);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * For each matched word, tries swapping in a different occurrence of the same token
     * if doing so tightens its fit against its immediate and next-nearest neighbors
     * (weighted 1.0 and 0.5 respectively). Runs up to {@link #INDEX_PROXIMITY_REFINEMENT_PASSES}
     * sweeps, stopping early once a sweep makes no changes.
     */
    private static MatchResult refineByLocalSwaps(MatchResult result, Map<String, List<Word>> index) {
        List<Word> path = new ArrayList<>(result.path);

        for (int pass = 0; pass < INDEX_PROXIMITY_REFINEMENT_PASSES; pass++) {
            boolean changed = false;
            for (int i = 0; i < path.size(); i++) {
                Word current = path.get(i);
                List<Word> alternatives = index.getOrDefault(current.token, Collections.emptyList());
                if (alternatives.size() <= 1) continue;

                double currentFit = localFitScore(path, i);
                Word bestAlt = null;
                double bestFit = currentFit;

                for (Word alt : alternatives) {
                    if (alt.sequenceIndex == current.sequenceIndex) continue;
                    if (path.stream().anyMatch(w -> w.sequenceIndex == alt.sequenceIndex)) continue;

                    path.set(i, alt);
                    double altFit = localFitScore(path, i);
                    path.set(i, current);

                    if (altFit < bestFit) {
                        bestFit = altFit;
                        bestAlt = alt;
                    }
                }

                if (bestAlt != null) {
                    path.set(i, bestAlt);
                    changed = true;
                }
            }
            if (!changed) break;
        }

        double totalGap = 0;
        for (int i = 0; i < path.size() - 1; i++) {
            totalGap += Math.abs(path.get(i + 1).sequenceIndex - path.get(i).sequenceIndex);
        }
        double newScore = path.size() > 1 ? totalGap / (path.size() - 1) : 0;
        return new MatchResult(path, newScore, result.matchedTokens);
    }

    /** Lower = tighter fit against neighbors (weight 1.0 immediate, 0.5 next-nearest). Lower is better. */
    private static double localFitScore(List<Word> path, int position) {
        if (path.size() <= 1) return 0;
        Word current = path.get(position);
        double totalDist = 0;
        int neighbors = 0;

        if (position > 0) {
            totalDist += Math.abs(current.sequenceIndex - path.get(position - 1).sequenceIndex);
            neighbors++;
        }
        if (position < path.size() - 1) {
            totalDist += Math.abs(current.sequenceIndex - path.get(position + 1).sequenceIndex);
            neighbors++;
        }
        if (position > 1) {
            totalDist += Math.abs(current.sequenceIndex - path.get(position - 2).sequenceIndex) * 0.5;
            neighbors++;
        }
        if (position < path.size() - 2) {
            totalDist += Math.abs(current.sequenceIndex - path.get(position + 2).sequenceIndex) * 0.5;
            neighbors++;
        }
        return neighbors > 0 ? totalDist / neighbors : 0;
    }

    // ==================================================================
    // Render
    // ==================================================================

    /**
     * Wraps each span element that contributed a matched word in {@code <mark>}. Several
     * matched {@link Word}s can share one span (one span, multiple tokens) — each element
     * is wrapped exactly once, otherwise the second match on the same span would nest a
     * {@code <mark>} inside the first one's.
     */
    private static String renderHighlights(List<Word> matchedWords, List<Word> allWords) {
        if (matchedWords.isEmpty()) return null;
        Document doc = matchedWords.get(0).span.ownerDocument();

        Set<Element> wrapped = new HashSet<>();
        for (Word w : matchedWords) {
            if (w.span == null || !wrapped.add(w.span)) continue;

            Element mark = new Element("mark");
            mark.attr("style", "background-color: yellow; color: inherit;");
            for (Node child : new ArrayList<>(w.span.childNodes())) {
                child.remove();
                mark.appendChild(child);
            }
            w.span.empty();
            w.span.appendChild(mark);
        }
        return doc.html();
    }
}
