package com.shiggsy365.bingetvredux;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** The stream recommended is the first this TV and connection can manage. */
public class StreamPickTest {
    private static StreamPick.Candidate c(double mbps, int height) {
        return new StreamPick.Candidate(mbps, height, true);
    }

    private static final List<StreamPick.Candidate> FILM = Arrays.asList(c(27, 2160), c(17, 1080), c(2.5, 1080));

    private static StreamPick.Candidate c(double mbps, int height, int language) {
        return new StreamPick.Candidate(mbps, height, true, language);
    }

    private static final int IN = StreamPick.IN_LANGUAGE, ALSO = StreamPick.ALSO_OTHER_LANGUAGES,
            OTHER = StreamPick.OTHER_LANGUAGE;
    private static final List<String> ENGLISH = Arrays.asList("eng");

    @Test
    public void prefersTheProfilesLanguage() {
        assertEquals(1, StreamPick.best(Arrays.asList(c(7.3, 1080, OTHER), c(7, 1080, IN)), 100));
    }

    @Test
    public void anotherLanguageIsBetterThanNothing() {
        assertEquals(1, StreamPick.best(Arrays.asList(
                new StreamPick.Candidate(7.3, 1080, false, IN), c(7, 1080, OTHER)), 100));
    }

    @Test
    public void onlyTheProfilesLanguageBeforeReleasesWithOthers() {
        // "2xRus.Ukr.Eng": English is in there, but Russian may well be what plays.
        assertEquals(1, StreamPick.best(Arrays.asList(c(7, 2160, ALSO), c(7, 2160, IN)), 100));
        assertEquals(0, StreamPick.best(Arrays.asList(c(7, 2160, ALSO), c(7, 1080, OTHER)), 100));
    }

    @Test
    public void theLanguageComesBeforeTheConnection() {
        // The connection measured is a guess; a stream in Russian is no use however well it'd play.
        assertEquals(1, StreamPick.best(Arrays.asList(c(7, 1080, OTHER), c(20, 2160, IN)), 10));
        assertEquals(1, StreamPick.best(Arrays.asList(c(20, 2160, IN), c(6, 1080, IN), c(5, 1080, OTHER)), 10));
    }

    @Test
    public void readsLanguagesFromTheFileName() {
        assertEquals(OTHER, StreamPick.language("", "Black.Doves.s02e01.HD1080p.WEBRip.Rus.RuDub.tv.mkv", ENGLISH));
        assertEquals(IN, StreamPick.language("", "Black Doves S02E01 1080p NF WEB-DL DDP5 1 Atmos H 264-FLUX.mkv", ENGLISH));
        assertEquals(IN, StreamPick.language("", "The.Italian.Job.2003.1080p.BluRay.x264.mkv", ENGLISH));
        assertEquals(ALSO, StreamPick.language("", "Film.2020.1080p.Rus.Eng.mkv", ENGLISH));
        assertEquals(ALSO, StreamPick.language("", "Film.2020.1080p.MULTI.mkv", ENGLISH));
        assertEquals(ALSO, StreamPick.language("", "Interstellar.2014.1080p.WEB-DL.2xRus.Ukr.Eng.HDCLUB.mkv", ENGLISH));
        assertEquals(OTHER, StreamPick.language("", "Film.2020.1080p.WEB-DL.MVO.mkv", ENGLISH));
        assertEquals(OTHER, StreamPick.language("", "Интерстеллар.2014.1080p.mkv", ENGLISH));
    }

    @Test
    public void anAddonsLanguageListWins() {
        assertEquals(ALSO, StreamPick.language("EN · FR", "Film.2020.Rus.mkv", ENGLISH));
        assertEquals(OTHER, StreamPick.language("RU", "Film.2020.1080p.mkv", ENGLISH));
        assertEquals(IN, StreamPick.language("en", "Film.2020.1080p.mkv", ENGLISH));
        // Listed with English, but the file says Russian too.
        assertEquals(ALSO, StreamPick.language("en · sub", "Film.2020.1080p.2xRus.Eng.mkv", ENGLISH));
    }

    @Test
    public void subtitlesArentALanguageList() {
        // "sub" alone: the addon says nothing of the audio, so the file name decides.
        assertEquals(IN, StreamPick.language("sub", "Film (2020) {imdb-tt1} [WEBDL-2160p][AAC 5.1].mkv", ENGLISH));
        assertEquals(OTHER, StreamPick.language("sub", "Film.2020.PL.iNTERNAL.2160p.WEBRip.mkv", ENGLISH));
    }

    @Test
    public void oneMeasurementDoesntDecideTheConnection() {
        assertEquals(40, StreamPick.averaged(0, 40), 1e-9);
        assertEquals(25, StreamPick.averaged(40, 10), 1e-9);
    }

    @Test
    public void keepsTheOrderWhenEverythingFits() {
        assertEquals(0, StreamPick.best(FILM, 100));
    }

    @Test
    public void leavesRoomToSpareOnTheConnection() {
        assertEquals(1, StreamPick.best(FILM, 25));
        assertEquals(2, StreamPick.best(FILM, 20));
    }

    @Test
    public void hidesWhatTheScreenCantShow() {
        assertEquals(Arrays.asList(1, 2), StreamPick.withinScreen(new int[]{2160, 1080, 720}, 1080));
        assertEquals(Arrays.asList(0, 1, 2), StreamPick.withinScreen(new int[]{2160, 1080, 720}, 2160));
    }

    @Test
    public void keepsStreamsOfUnknownHeight() {
        assertEquals(Arrays.asList(1), StreamPick.withinScreen(new int[]{2160, 0}, 1080));
    }

    @Test
    public void keepsEverythingWhenNothingWouldBeLeftOrTheScreenDoesntSay() {
        assertEquals(Arrays.asList(0, 1), StreamPick.withinScreen(new int[]{2160, 1440}, 1080));
        assertEquals(Arrays.asList(0, 1), StreamPick.withinScreen(new int[]{2160, 1080}, 0));
    }

    @Test
    public void limitsNotKnownArentApplied() {
        assertEquals(0, StreamPick.best(FILM, 0));
        assertEquals(0, StreamPick.best(Arrays.asList(c(0, 0), c(1, 720)), 5));
    }

    @Test
    public void skipsStreamsThatAreNotReady() {
        assertEquals(1, StreamPick.best(Arrays.asList(new StreamPick.Candidate(1, 1080, false), c(1, 1080)), 50));
    }

    @Test
    public void fallsBackToTheFirstWhenNothingFits() {
        assertEquals(0, StreamPick.best(FILM, 1));
    }

    @Test
    public void readsBitrates() {
        assertEquals(26.4, StreamPick.mbps("26.4 Mbps"), 1e-9);
        assertEquals(0.991, StreamPick.mbps("991 Kbps"), 1e-9);
        assertEquals(0, StreamPick.mbps(""), 0);
    }

    @Test
    public void readsResolutions() {
        assertEquals(2160, StreamPick.height("4k"));
        assertEquals(1080, StreamPick.height("1080p"));
        assertEquals(0, StreamPick.height(""));
    }
}
