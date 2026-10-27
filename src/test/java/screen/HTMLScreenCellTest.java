package screen;

import com.automationanywhere.botcommand.utilities.logger.HTMLGenerator;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class HTMLScreenCellTest {
    // The generator does no file I/O, so the folder does not have to exist.
    private static final Path DIR = Paths.get("html-screen-cell-test").toAbsolutePath();

    private static String file(String folder, String name) {
        return DIR.resolve(folder).resolve(name).toString();
    }

    @Test
    public void videoTakesPriorityOverScreenshot() {
        String html = HTMLGenerator.getScreenCellHTML(
                file("screenshots", "warn_uuid.png"),
                file("clips", "error_uuid.mp4"),
                file("screenshots", "error_uuid.png"), DIR);

        Assert.assertTrue(html.contains("video-link"),
                "video-link class should be applied when video path is present");
        Assert.assertTrue(html.contains("href='clips/error_uuid.mp4'"),
                "video href should be relative to the HTML folder");
        Assert.assertTrue(html.contains("src='screenshots/error_uuid.png'"),
                "poster should be relative to the HTML folder");
        Assert.assertTrue(html.contains("&#9654;") || html.contains("▶"),
                "play overlay glyph should be present");
    }

    @Test
    public void videoOnlyWithoutPoster() {
        String html = HTMLGenerator.getScreenCellHTML("", file("clips", "x.mp4"), "", DIR);

        Assert.assertTrue(html.contains("video-link"));
        Assert.assertTrue(html.contains("href='clips/x.mp4'"));
        // No img tag if no poster
        Assert.assertFalse(html.contains("<img"),
                "no poster image expected when poster path is empty");
    }

    @Test
    public void emptyEverythingProducesEmptyCell() {
        Assert.assertEquals(HTMLGenerator.getScreenCellHTML("", "", "", DIR), "");
        Assert.assertEquals(HTMLGenerator.getScreenCellHTML(null, null, null, DIR), "");
    }

    @Test
    public void videoUnavailableShowsPosterWithBadge() {
        // Recording was requested for this row (poster captured) but the
        // encoder pool rejected the task or the recorder failed: videoPath
        // empty, videoPosterPath set. The cell should show the poster as a
        // clickable image (linking to itself) with a "no video" badge.
        String html = HTMLGenerator.getScreenCellHTML(
                file("screenshots", "error_uuid.png"),
                "",
                file("screenshots", "error_uuid.png"), DIR);

        Assert.assertTrue(html.contains("video-unavailable"),
                "video-unavailable class should be applied when poster exists but video is missing");
        Assert.assertTrue(html.contains("no-video-badge"),
                "no-video badge span should be present");
        Assert.assertFalse(html.contains("video-link"),
                "video-link class must not be applied when there is no video");
        Assert.assertFalse(html.contains("play-overlay"),
                "play overlay must not appear when there is no video");
        Assert.assertTrue(html.contains("href='screenshots/error_uuid.png'") && html.contains("src='screenshots/error_uuid.png'"),
                "the link target and img src should both be the poster screenshot");
    }
}
