package app.voidhack.extension.applemusic.lyrics;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class LyricsEditTtmlTest {
    private static final String TTML_NS = "http://www.w3.org/ns/ttml";
    private static final String APPLE_NS = "http://music.apple.com/lyric-ttml-internal";
    private static final String WORD_HEADER = "<tt xmlns=\"" + TTML_NS + "\" xmlns:itunes=\"" + APPLE_NS
            + "\" itunes:timing=\"Word\" xml:lang=\"ja\">";
    private static final String WORD_LINES = "<p itunes:key=\"L1\" begin=\"00:01.000\" end=\"00:04.000\">"
            + "<span begin=\"00:01.000\" end=\"00:02.000\">星</span>"
            + "<span begin=\"00:02.000\" end=\"00:04.000\">の歌</span></p>"
            + "<p itunes:key=\"L2\" begin=\"00:05.000\" end=\"00:08.000\">"
            + "<span begin=\"00:05.000\" end=\"00:06.000\">星</span>"
            + "<span begin=\"00:06.000\" end=\"00:08.000\">の歌</span></p>";

    @Test
    public void replacesOnlyTheChosenOccurrenceAndKeepsItsLineTiming() throws Exception {
        String base = WORD_HEADER + "<body><div>" + WORD_LINES + "</div></body></tt>";

        String changed = LyricsEditTtml.applyText(base, Map.of(1, new LyricsEditTtml.Edit("空 & 海", null)));

        Document parsed = OfficialLyricsTtml.parse(changed);
        Element first = (Element) parsed.getElementsByTagNameNS(TTML_NS, "p").item(0);
        assertEquals("星の歌", first.getTextContent());
        assertEquals(2, first.getElementsByTagNameNS(TTML_NS, "span").getLength());
        Element second = (Element) parsed.getElementsByTagNameNS(TTML_NS, "p").item(1);
        NodeList words = second.getElementsByTagNameNS(TTML_NS, "span");
        assertEquals(1, words.getLength());
        Element span = (Element) words.item(0);
        assertEquals("空 & 海", span.getTextContent());
        assertEquals("00:05.000", span.getAttribute("begin"));
        assertEquals("00:08.000", span.getAttribute("end"));
    }

    @Test
    public void updatesOneReadingAndPreservesOtherOfficialReadingsAndTheTranslation() throws Exception {
        String base = WORD_HEADER + "<head><metadata><iTunesMetadata xmlns=\"" + APPLE_NS + "\">"
                + "<translations><translation xml:lang=\"en\"><text for=\"L2\">Song of the stars</text>"
                + "</translation></translations><transliterations>"
                + "<transliteration xml:lang=\"ja-Latn\"><text for=\"L1\">Official first</text>"
                + "<text for=\"L2\">Official second</text></transliteration>"
                + "</transliterations></iTunesMetadata></metadata></head>"
                + "<body><div>" + WORD_LINES + "</div></body></tt>";

        String changed = LyricsEditTtml.applyReadings(base, Map.of(1, new LyricsEditTtml.Edit(null, "hoshi no uta")));

        List<LyricsEditTtml.Row> rows = LyricsEditTtml.rows(changed);
        assertEquals("Official first", rows.get(0).reading);
        assertEquals("hoshi no uta", rows.get(1).reading);
        Document parsed = OfficialLyricsTtml.parse(changed);
        assertEquals(1, parsed.getElementsByTagNameNS(APPLE_NS, "transliteration").getLength());
        assertEquals("Song of the stars", parsed.getElementsByTagNameNS(APPLE_NS, "translation").item(0)
                .getTextContent());
    }

    @Test
    public void createsACompleteReadingTrackIncludingAWordTimedGap() throws Exception {
        String base = WORD_HEADER + "<body><div>"
                + "<p begin=\"00:00.000\" end=\"00:01.000\"></p>" + WORD_LINES + "</div></body></tt>";

        String changed = LyricsEditTtml.applyReadings(base, Map.of(2, new LyricsEditTtml.Edit(null, "hoshi no uta")));

        Document parsed = OfficialLyricsTtml.parse(changed);
        NodeList lines = parsed.getElementsByTagNameNS(TTML_NS, "p");
        NodeList guide = parsed.getElementsByTagNameNS(APPLE_NS, "text");
        assertEquals(3, guide.getLength());
        for (int i = 0; i < guide.getLength(); i++) {
            Element line = (Element) lines.item(i);
            Element reading = (Element) guide.item(i);
            assertEquals(line.getAttributeNS(APPLE_NS, "key"), reading.getAttribute("for"));
            Element span = (Element) reading.getFirstChild();
            assertEquals(line.getAttribute("begin"), span.getAttribute("begin"));
            assertEquals(line.getAttribute("end"), span.getAttribute("end"));
            assertEquals(i == 2 ? "hoshi no uta" : "", span.getTextContent());
        }
    }

}
