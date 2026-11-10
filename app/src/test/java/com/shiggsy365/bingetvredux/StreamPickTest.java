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

    @Test
    public void prefersTheProfilesLanguage() {
        assertEquals(1, StreamPick.best(Arrays.asList(
                new StreamPick.Candidate(7.3, 1080, true, false), new StreamPick.Candidate(7, 1080, true, true)), 100));
    }

    @Test
    public void anotherLanguageIsBetterThanNothing() {
        assertEquals(1, StreamPick.best(Arrays.asList(
                new StreamPick.Candidate(7.3, 1080, false, true), new StreamPick.Candidate(7, 1080, true, false)), 100));
    }

    @Test
    public void readsLanguagesFromTheFileName() {
        List<String> english = Arrays.asList("eng");
        assertEquals(false, StreamPick.inLanguage("", "Black.Doves.s02e01.HD1080p.WEBRip.Rus.RuDub.tv.mkv", english));
        assertEquals(true, StreamPick.inLanguage("", "Black Doves S02E01 1080p NF WEB-DL DDP5 1 Atmos H 264-FLUX.mkv", english));
        assertEquals(true, StreamPick.inLanguage("", "The.Italian.Job.2003.1080p.BluRay.x264.mkv", english));
        assertEquals(true, StreamPick.inLanguage("", "Film.2020.1080p.Rus.Eng.mkv", english));
        assertEquals(true, StreamPick.inLanguage("", "Film.2020.1080p.MULTI.mkv", english));
    }

    @Test
    public void anAddonsLanguageListWins() {
        List<String> english = Arrays.asList("eng");
        assertEquals(true, StreamPick.inLanguage("EN · FR", "Film.2020.Rus.mkv", english));
        assertEquals(false, StreamPick.inLanguage("RU", "Film.2020.1080p.mkv", english));
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
