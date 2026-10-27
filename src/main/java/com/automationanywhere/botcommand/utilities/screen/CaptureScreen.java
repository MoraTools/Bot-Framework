package com.automationanywhere.botcommand.utilities.screen;

import com.automationanywhere.botcommand.exception.BotCommandException;
import org.apache.commons.io.FilenameUtils;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @author Sumit Kumar
 */

public class CaptureScreen {

    public static void captureDesktop(String filePath) {
        try {
            if (filePath == null || filePath.isBlank()) {
                throw new BotCommandException("Empty screenshot path provided");
            }

            Path path = Paths.get(filePath);
            File file = path.toFile();

            // Ensure parent directories exist
            Path parentDir = path.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }

            Robot robot = new Robot();
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            Rectangle screenRectangle = new Rectangle(screenSize);
            BufferedImage image = robot.createScreenCapture(screenRectangle);
            String extension = FilenameUtils.getExtension(filePath);
            saveFile(image, extension, file);
        } catch (Exception ex) {
            throw new BotCommandException("Unable to save screenshot: " + ex.getMessage(), ex);
        }
    }

    private static void saveFile(BufferedImage screenCapture, String extension, File fileToSave) throws IOException {
        if ("wmf".equalsIgnoreCase(extension)) {
            extension = "png";
        }

        ImageIO.write(screenCapture, extension, fileToSave);
    }
}

