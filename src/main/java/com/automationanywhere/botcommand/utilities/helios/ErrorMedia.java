package com.automationanywhere.botcommand.utilities.helios;

import com.automationanywhere.botcommand.utilities.screen.recorder.CaptureResult;
import java.nio.file.Path;

/** Caller-thread media registration, excluded from HTML and normal message fields. */
public final class ErrorMedia {
    public static final String COLUMN = "HELIOS_ERROR_MEDIA";
    public final Path screenshot;
    public final String screenshotFailure;
    public final CaptureResult capture;
    public final boolean video;
    public final boolean ownedScreenshot;

    public ErrorMedia(Path screenshot, String screenshotFailure, CaptureResult capture, boolean video) {
        this(screenshot, screenshotFailure, capture, video, false);
    }

    public ErrorMedia(Path screenshot, String screenshotFailure, CaptureResult capture, boolean video, boolean ownedScreenshot) {
        this.screenshot = screenshot;
        this.screenshotFailure = screenshotFailure;
        this.capture = capture;
        this.video = video;
        this.ownedScreenshot = ownedScreenshot;
    }
}
