package screen;

import com.automationanywhere.botcommand.utilities.logger.HTMLGenerator;
import org.testng.Assert;
import org.testng.annotations.Test;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class MediaMarkupParityTest {
    @Test
    public void compactThumbnailsKeepEveryLinkAttributeAndBadge() throws Exception {
        // The generator does no file I/O, so the folder does not have to exist.
        Path dir = Paths.get("media-markup-test").toAbsolutePath();
        // Names use only characters that every file system accepts; ' ( ) are URL-quoted, & is HTML-escaped.
        String path = "screenshots/image&amp;%281%29%27.png";
        String image = "<img src='" + path + "' loading='lazy' decoding='async' width='88' height='50' alt='' />";
        String preview = "style='--screenshot-preview: url(\"" + path + "\")'";
        String screenshot = "<a href='" + path + "' target='_blank' class='img-link' "
                + "aria-label='Screenshot' title='Screenshot' " + preview + ">" + image + "</a>";
        String video = "<a href='clips/video&amp;%281%29%27.mp4' target='_blank' class='img-link video-link' "
                + "aria-label='Recording' title='Open recording' " + preview + ">" + image
                + "<span class='play-overlay' aria-hidden='true'>&#9654;</span></a>";
        String unavailable = "<a href='" + path + "' target='_blank' class='img-link video-unavailable' "
                + "aria-label='Screenshot (video unavailable)' title='Video unavailable; opens screenshot' "
                + preview + ">" + image + "<span class='no-video-badge' aria-hidden='true'>no video</span></a>";
        String screenshotPath = dir.resolve("screenshots").resolve("image&(1)'.png").toString();
        String videoPath = dir.resolve("clips").resolve("video&(1)'.mp4").toString();
        assertSameMedia(screenshot, HTMLGenerator.getScreenCellHTML(screenshotPath, "", "", dir));
        assertSameMedia(video, HTMLGenerator.getScreenCellHTML("", videoPath, screenshotPath, dir));
        assertSameMedia(unavailable, HTMLGenerator.getScreenCellHTML("", "", screenshotPath, dir));
    }

    private static void assertSameMedia(String oldHtml, String newHtml) throws Exception {
        Assert.assertEquals(parsed(oldHtml), parsed(newHtml), "all parsed tags, attributes and badge text match");
        Assert.assertEquals(newHtml.length(), oldHtml.length() - 2, "omit the optional slash and space on img");
    }

    private static List<String> parsed(String html) throws Exception {
        List<String> nodes = new ArrayList<>();
        new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
            private void tag(String prefix, HTML.Tag tag, MutableAttributeSet attributes) {
                Map<String, String> values = new TreeMap<>();
                for (Enumeration<?> names = attributes.getAttributeNames(); names.hasMoreElements();) {
                    Object name = names.nextElement();
                    values.put(name.toString(), attributes.getAttribute(name).toString());
                }
                nodes.add(prefix + tag + values);
            }
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                tag("start:", tag, attributes);
            }
            @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                tag("simple:", tag, attributes);
            }
            @Override public void handleEndTag(HTML.Tag tag, int position) {
                nodes.add("end:" + tag);
            }
            @Override public void handleText(char[] data, int position) {
                nodes.add("text:" + new String(data));
            }
        }, true);
        return nodes;
    }
}
