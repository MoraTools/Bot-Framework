import com.google.javascript.jscomp.CommandLineRunner;
import com.google.javascript.jscomp.CompilationLevel;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.PropertyRenamingPolicy;
import com.google.javascript.jscomp.SourceFile;
import com.google.javascript.jscomp.VariableRenamingPolicy;
import com.helger.css.ECSSVersion;
import com.helger.css.decl.CascadingStyleSheet;
import com.helger.css.reader.CSSReader;
import com.helger.css.reader.CSSReaderSettings;
import com.helger.css.reader.errorhandler.ICSSInterpretErrorHandler;
import com.helger.css.reader.errorhandler.ThrowingCSSParseErrorHandler;
import com.helger.css.writer.CSSWriter;
import com.helger.css.writer.CSSWriterSettings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Java 11 source launcher, used only by Gradle. The readable template is the source of truth. */
class MinifyLog {
    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String prefix = "<!DOCTYPE html>\n<!-- a360-log-format:6 -->\n{{identity}}\n";
        String end = "<!-- a360-log-header:end -->\n";
        if (!source.startsWith(prefix) || !source.endsWith(end)) {
            throw new IllegalArgumentException("Log template boundary markers changed.");
        }
        String markup = source.substring(prefix.length(), source.length() - end.length());
        Matcher blocks = Pattern.compile("(?s)<(style|script)>(.*?)</\\1>").matcher(markup);
        StringBuilder compact = new StringBuilder(prefix);
        int position = 0, styles = 0, scripts = 0;
        while (blocks.find()) {
            compact.append(compactMarkup(markup.substring(position, blocks.start())));
            String kind = blocks.group(1);
            String code;
            if (kind.equals("style")) { styles++; code = css(blocks.group(2)); }
            else { scripts++; code = javascript(blocks.group(2)); }
            compact.append('<').append(kind).append('>').append(code).append("</").append(kind).append('>');
            position = blocks.end();
        }
        if (styles != 1 || scripts != 1) throw new IllegalArgumentException("Expected one inline style and script.");
        compact.append(compactMarkup(markup.substring(position))).append('\n').append(end);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.writeString(output, compact, StandardCharsets.UTF_8);
        System.out.println("Log viewer: " + source.getBytes(StandardCharsets.UTF_8).length + " -> "
                + compact.toString().getBytes(StandardCharsets.UTF_8).length + " bytes");
    }

    private static String compactMarkup(String markup) {
        // Only template markup reaches this method. CSS and JavaScript use their own parsers.
        return markup.replaceAll("[\\t ]*\\n[\\t ]*", "");
    }

    private static String javascript(String source) throws Exception {
        CompilerOptions options = new CompilerOptions();
        CompilationLevel.SIMPLE_OPTIMIZATIONS.setOptionsForCompilationLevel(options);
        options.setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_NEXT);
        options.setLanguageOut(CompilerOptions.LanguageMode.ECMASCRIPT_2017);
        options.setRenamingPolicy(VariableRenamingPolicy.LOCAL, PropertyRenamingPolicy.OFF);
        options.setRewritePolyfills(false);
        options.setEmitUseStrict(false);
        options.setLineLengthThreshold(Integer.MAX_VALUE);
        options.setTrustedStrings(false);
        com.google.javascript.jscomp.Compiler compiler = new com.google.javascript.jscomp.Compiler();
        if (!compiler.compile(CommandLineRunner.getDefaultExterns(),
                List.of(SourceFile.fromCode("log-viewer.js", source)), options).success) {
            throw new IllegalArgumentException("Log viewer JavaScript minification failed.");
        }
        return compiler.toSource();
    }

    private static String css(String source) {
        CSSReaderSettings reader = new CSSReaderSettings().setCSSVersion(ECSSVersion.CSS30)
                .setCustomErrorHandler(new ThrowingCSSParseErrorHandler())
                .setCustomExceptionHandler(error -> { throw new IllegalArgumentException("Log viewer CSS parse failed.", error); })
                .setInterpretErrorHandler(new ICSSInterpretErrorHandler() {
                    public void onCSSInterpretationWarning(String message) { throw new IllegalArgumentException(message); }
                    public void onCSSInterpretationError(String message) { throw new IllegalArgumentException(message); }
                });
        CascadingStyleSheet sheet = CSSReader.readFromStringReader(source, reader);
        if (sheet == null) throw new IllegalArgumentException("Log viewer CSS minification failed.");
        return new CSSWriter(new CSSWriterSettings(ECSSVersion.CSS30).setOptimizedOutput(true)
                .setRemoveUnnecessaryCode(false)).getCSSAsString(sheet);
    }
}
