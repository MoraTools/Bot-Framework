package device;

import com.automationanywhere.botcommand.actions.device.CreateFolders;
import com.automationanywhere.botcommand.actions.device.CreateFoldersCommand;
import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.BooleanValue;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.ListValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import support.TempDirectories;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * @author Sumit Kumar
 */

public class CreateFoldersTest {
    private static final String RELATIVE_PATH_TYPE = "RELATIVE";
    private static final String ABSOLUTE_PATH_TYPE = "ABSOLUTE";
    private final TempDirectories temp = new TempDirectories();
    private CreateFolders createFolders;
    private Path testBasePath;

    @BeforeMethod
    public void setUp() throws Exception {
        createFolders = new CreateFolders();
        testBasePath = temp.create("create-folder-test-");
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        temp.deleteAll();
    }

    @Test
    public void testCreateFoldersRelativePath() {
        String pathType = RELATIVE_PATH_TYPE;
        String baseFolderPath = testBasePath.resolve("base").toString();
        List<Value> folderPaths = List.of(
                new StringValue("folder1"),
                new StringValue("folder2/subfolder"),
                new StringValue("folder3/subfolder1/subfolder2/subfolder3")
        );
        Boolean overwriteExisting = false;
        Boolean appendToExistingDictionary = false;
        Map<String, Value> configDictionary = new HashMap<>();
        String suffix = "_path";
        String prefix = "test_";

        DictionaryValue output = createFolders.action(pathType, baseFolderPath, folderPaths, overwriteExisting,
                appendToExistingDictionary, configDictionary, suffix, prefix);

        assertFolderCreation(output, prefix, suffix);
    }

    private void assertFolderCreation(DictionaryValue output, String prefix, String suffix) {
        Map<String, Value> dictionary = output.get();
        for (Map.Entry<String, Value> entry : dictionary.entrySet()) {
            String folderName = entry.getKey().replace(prefix, "").replace(suffix, "");
            Path folderPath = Path.of(entry.getValue().get().toString());
            Assert.assertTrue(Files.exists(folderPath), "Folder does not exist: " + folderName);
        }
    }

    @Test
    public void testCreateFoldersAbsolutePath() {
        String pathType = ABSOLUTE_PATH_TYPE;
        String baseFolderPath = ""; // Not used in ABSOLUTE path mode
        List<Value> folderPaths = List.of(
                new StringValue(testBasePath.resolve("base/folder1").toString()),
                new StringValue(testBasePath.resolve("base/folder2/subfolder").toString()),
                new StringValue(testBasePath.resolve("base/folder3/subfolder1/subfolder2/subfolder3").toString())
        );
        Boolean overwriteExisting = true;
        Boolean appendToExistingDictionary = false;
        Map<String, Value> configDictionary = new HashMap<>();
        String suffix = "_path";
        String prefix = "test_";

        DictionaryValue output = createFolders.action(pathType, baseFolderPath, folderPaths, overwriteExisting,
                appendToExistingDictionary, configDictionary, suffix, prefix);

        assertFolderCreation(output, prefix, suffix);
    }

    @DataProvider
    public Object[][] invalidRelativePaths() {
        return new Object[][]{
                {""}, {" "}, {"."}, {"child/.."}, {".."}, {"../outside"}, {"child/../.."}
        };
    }

    @Test(dataProvider = "invalidRelativePaths")
    public void testInvalidBatchPreservesExistingFolders(String invalidPath) throws Exception {
        Path base = Files.createDirectories(testBasePath.resolve("base"));
        Path existing = Files.createDirectories(base.resolve("existing"));
        Path marker = Files.writeString(existing.resolve("marker.txt"), "keep");
        Path baseMarker = Files.writeString(base.resolve("base-marker.txt"), "keep");

        Assert.expectThrows(BotCommandException.class, () -> createFolders.action(RELATIVE_PATH_TYPE,
                base.toString(), List.of(new StringValue("existing"), new StringValue(invalidPath)),
                true, false, null, null, null));

        Assert.assertEquals(Files.readString(marker), "keep");
        Assert.assertEquals(Files.readString(baseMarker), "keep");
    }

    @Test
    public void testBlankItemThroughSdkWrapperPreservesBaseFolder() throws Exception {
        Path base = Files.createDirectories(testBasePath.resolve("base"));
        Path marker = Files.writeString(base.resolve("marker.txt"), "keep");
        Map<String, Value> parameters = new HashMap<>();
        parameters.put("pathType", new StringValue(RELATIVE_PATH_TYPE));
        parameters.put("baseFolderPath", new StringValue(base.toString()));
        parameters.put("folderPaths", new ListValue(new StringValue("")));
        parameters.put("overwriteExisting", new BooleanValue(true));
        parameters.put("appendToExistingDictionary", new BooleanValue(false));

        Assert.expectThrows(BotCommandException.class,
                () -> new CreateFoldersCommand().execute(null, parameters, new HashMap<>()));
        Assert.assertEquals(Files.readString(marker), "keep");
    }

    @Test
    public void testRootAndFileParentRejectedBeforeOverwrite() throws Exception {
        Path existing = Files.createDirectories(testBasePath.resolve("existing"));
        Path marker = Files.writeString(existing.resolve("marker.txt"), "keep");
        Path fileParent = Files.writeString(testBasePath.resolve("file.txt"), "keep");
        Path newFolder = testBasePath.resolve("new-folder");

        // Overwrite stays off for the root: if the root check regressed, the test must not delete a real root.
        Assert.expectThrows(BotCommandException.class, () -> createFolders.action(ABSOLUTE_PATH_TYPE,
                null, List.of(new StringValue(newFolder.toString()), new StringValue(testBasePath.getRoot().toString())),
                false, false, null, null, null));
        Assert.assertFalse(Files.exists(newFolder));

        Assert.expectThrows(BotCommandException.class, () -> createFolders.action(ABSOLUTE_PATH_TYPE,
                null, List.of(new StringValue(existing.toString()), new StringValue(fileParent.resolve("child").toString())),
                true, false, null, null, null));
        Assert.assertEquals(Files.readString(marker), "keep");
        Assert.assertEquals(Files.readString(fileParent), "keep");
    }

    @Test
    public void testSymlinkTargetsStayInsideBaseFolder() throws Exception {
        Path base = Files.createDirectories(testBasePath.resolve("base"));
        Path existing = Files.createDirectories(base.resolve("existing"));
        Path marker = Files.writeString(existing.resolve("marker.txt"), "keep");
        Path outside = Files.createDirectories(testBasePath.resolve("outside"));
        Path outsideMarker = Files.writeString(outside.resolve("marker.txt"), "keep");
        try {
            Files.createSymbolicLink(base.resolve("link"), outside);
            Files.createSymbolicLink(base.resolve("base-link"), base);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            throw new SkipException("Symbolic links are unavailable", e);
        }
        for (String invalidPath : List.of("link/child", "link/../outside", "base-link")) {
            Assert.expectThrows(BotCommandException.class, () -> createFolders.action(RELATIVE_PATH_TYPE,
                    base.toString(), List.of(new StringValue("existing"), new StringValue(invalidPath)),
                    true, false, null, null, null));
            Assert.assertEquals(Files.readString(marker), "keep");
            Assert.assertEquals(Files.readString(outsideMarker), "keep");
        }
    }

    @Test
    public void testValidBaseAndParentSymlinksAndDotSegments() throws Exception {
        Path base = Files.createDirectories(testBasePath.resolve("base"));
        Files.createDirectories(base.resolve("inside"));
        Path baseLink = testBasePath.resolve("base-link");
        try {
            Files.createSymbolicLink(baseLink, base);
            Files.createSymbolicLink(base.resolve("inside-link"), base.resolve("inside"));
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            throw new SkipException("Symbolic links are unavailable", e);
        }

        DictionaryValue result = createFolders.action(RELATIVE_PATH_TYPE, baseLink.toString(),
                List.of(new StringValue("inside-link/child"), new StringValue("inside/../sibling")),
                false, false, null, null, null);

        Assert.assertTrue(Files.isDirectory(base.resolve("inside/child")));
        Assert.assertTrue(Files.isDirectory(base.resolve("sibling")));
        Assert.assertEquals(result.get("child").get(), baseLink.resolve("inside-link/child").toString());
        Assert.assertEquals(result.get("sibling").get(), baseLink.resolve("inside/../sibling").toString());
    }

    @Test
    public void testValidOverwriteKeepsBaseAndAppendsDictionary() throws Exception {
        Path base = Files.createDirectories(testBasePath.resolve("base"));
        Path existing = Files.createDirectories(base.resolve("existing"));
        Path oldFile = Files.writeString(existing.resolve("old.txt"), "replace");
        Path baseMarker = Files.writeString(base.resolve("marker.txt"), "keep");
        Map<String, Value> config = new HashMap<>();
        config.put("original", new StringValue("keep"));

        DictionaryValue result = createFolders.action(RELATIVE_PATH_TYPE, base.toString(),
                List.of(new StringValue("existing"), new StringValue("new/child")),
                true, true, config, "_Path", "test_");

        Assert.assertFalse(Files.exists(oldFile));
        Assert.assertEquals(Files.readString(baseMarker), "keep");
        Assert.assertTrue(Files.isDirectory(existing));
        Assert.assertTrue(Files.isDirectory(base.resolve("new/child")));
        Assert.assertEquals(result.get("test_existing_Path").get(), existing.toString());
        Assert.assertEquals(result.get("test_child_Path").get(), base.resolve("new/child").toString());
        Assert.assertEquals(config.get("original").get(), "keep");
        Assert.assertEquals(config.get("test_existing_Path").get(), existing.toString());
        Assert.assertEquals(config.get("test_child_Path").get(), base.resolve("new/child").toString());
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            throw new SkipException("Symbolic links are unavailable", e);
        }
    }

    @DataProvider
    public Object[][] relativeEntriesInAbsoluteMode() {
        // The unique name stands for a plain name such as Output. Overwrite stays off in the test, so a
        // regression cannot delete the working directory.
        return new Object[][]{
                {"."}, {".."}, {"C:"}, {"create-folders-" + UUID.randomUUID()}
        };
    }

    @Test(dataProvider = "relativeEntriesInAbsoluteMode")
    public void testAbsoluteModeRejectsRelativeEntries(String relativeEntry) {
        Path newFolder = testBasePath.resolve("new-folder");

        BotCommandException e = Assert.expectThrows(BotCommandException.class, () -> createFolders.action(
                ABSOLUTE_PATH_TYPE, null,
                List.of(new StringValue(newFolder.toString()), new StringValue(relativeEntry)),
                false, false, null, null, null));

        Assert.assertTrue(e.getMessage().endsWith(relativeEntry), e.getMessage());
        Assert.assertFalse(Files.exists(newFolder));
    }

    @Test
    public void testMissingRootGivesClearError() throws Exception {
        // Linux always has "/", so a relative path with a missing first element is the closest way to reach
        // the case of an unmapped drive or an offline share. The method is private and has no other seam.
        Path missing = Path.of("create-folders-" + UUID.randomUUID(), "child");
        Method resolveExistingParents = CreateFolders.class.getDeclaredMethod("resolveExistingParents", Path.class);
        resolveExistingParents.setAccessible(true);

        InvocationTargetException e = Assert.expectThrows(InvocationTargetException.class,
                () -> resolveExistingParents.invoke(createFolders, missing));

        Assert.assertTrue(e.getCause() instanceof BotCommandException, String.valueOf(e.getCause()));
        Assert.assertEquals(e.getCause().getMessage(), "Root of folder path does not exist: " + missing);
    }

    @Test
    public void testErrorWithoutMessageStillNamesTheCause() {
        Value<String> brokenValue = () -> {
            throw new IllegalStateException();
        };
        List<Value> folderPaths = List.of(brokenValue);

        BotCommandException e = Assert.expectThrows(BotCommandException.class, () -> createFolders.action(
                ABSOLUTE_PATH_TYPE, null, folderPaths, false, false, null, null, null));

        Assert.assertEquals(e.getMessage(), "java.lang.IllegalStateException");
    }

    @Test
    public void testMissingOverwriteFlagMeansOff() {
        Path folder = testBasePath.resolve("folder");

        createFolders.action(ABSOLUTE_PATH_TYPE, null, List.of(new StringValue(folder.toString())),
                null, false, null, null, null);

        Assert.assertTrue(Files.isDirectory(folder));
    }

    @Test
    public void testMissingAppendFlagMeansOff() {
        Path folder = testBasePath.resolve("folder");
        Map<String, Value> config = new HashMap<>();
        config.put("original", new StringValue("keep"));

        DictionaryValue result = createFolders.action(ABSOLUTE_PATH_TYPE, null, List.of(new StringValue(folder.toString())),
                false, null, config, null, null);

        Assert.assertTrue(Files.isDirectory(folder));
        Assert.assertEquals(result.get("folder").get(), folder.toString());
        Assert.assertEquals(config.keySet(), Set.of("original"));
    }

    @DataProvider
    public Object[][] overlappingEntries() {
        return new Object[][]{
                {"ab/sub", "ab"}, {"ab", "ab/sub"}, {"ab", "ab"}, {"ab/sub", "ab/../ab/sub"}
        };
    }

    @Test(dataProvider = "overlappingEntries")
    public void testOverwriteRejectsEqualOrNestedEntries(String first, String second) throws Exception {
        Path base = Files.createDirectories(testBasePath.resolve("base"));
        Path existing = Files.createDirectories(base.resolve("ab"));
        Path marker = Files.writeString(existing.resolve("marker.txt"), "keep");

        Assert.expectThrows(BotCommandException.class, () -> createFolders.action(RELATIVE_PATH_TYPE,
                base.toString(), List.of(new StringValue(first), new StringValue(second)),
                true, false, null, null, null));

        Assert.assertEquals(Files.readString(marker), "keep");
        Assert.assertFalse(Files.exists(existing.resolve("sub")));
    }

    @Test
    public void testOverwriteRejectsEntriesThatMeetThroughASymbolicLink() throws Exception {
        Path base = Files.createDirectories(testBasePath.resolve("base"));
        Path real = Files.createDirectories(base.resolve("real"));
        Path marker = Files.writeString(real.resolve("marker.txt"), "keep");
        createSymbolicLinkOrSkip(base.resolve("alias"), real);

        Assert.expectThrows(BotCommandException.class, () -> createFolders.action(RELATIVE_PATH_TYPE,
                base.toString(), List.of(new StringValue("alias/child"), new StringValue("real")),
                true, false, null, null, null));

        Assert.assertEquals(Files.readString(marker), "keep");
        Assert.assertFalse(Files.exists(real.resolve("child")));
    }

    @Test
    public void testOverlappingEntriesAreAllowedWhenOverwriteIsOff() throws Exception {
        Path base = Files.createDirectories(testBasePath.resolve("base"));
        Path existing = Files.createDirectories(base.resolve("ab"));
        Path marker = Files.writeString(existing.resolve("marker.txt"), "keep");

        DictionaryValue result = createFolders.action(RELATIVE_PATH_TYPE, base.toString(),
                List.of(new StringValue("ab/sub"), new StringValue("ab")), false, false, null, null, null);

        Assert.assertEquals(result.get().size(), 2);
        Assert.assertTrue(Files.isDirectory(existing.resolve("sub")));
        Assert.assertEquals(Files.readString(marker), "keep");
    }

    @DataProvider
    public Object[][] linkTargets() {
        return new Object[][]{{"outside"}, {"outside/marker.txt"}};
    }

    // A symbolic link is the closest Linux vector for a Windows junction: a junction cannot be made here.
    @Test(dataProvider = "linkTargets")
    public void testOverwriteRefusesFolderWithSymbolicLinkAndChangesNothing(String linkTarget) throws Exception {
        Path base = Files.createDirectories(testBasePath.resolve("base"));
        Path safe = Files.createDirectories(base.resolve("safe"));
        Path safeMarker = Files.writeString(safe.resolve("marker.txt"), "keep");
        Path inner = Files.createDirectories(base.resolve("risky/inner"));
        Path innerMarker = Files.writeString(inner.resolve("marker.txt"), "keep");
        Path outsideMarker = Files.writeString(
                Files.createDirectories(testBasePath.resolve("outside")).resolve("marker.txt"), "keep");
        Path link = inner.resolve("link");
        createSymbolicLinkOrSkip(link, testBasePath.resolve(linkTarget));

        BotCommandException e = Assert.expectThrows(BotCommandException.class, () -> createFolders.action(
                RELATIVE_PATH_TYPE, base.toString(), List.of(new StringValue("safe"), new StringValue("risky")),
                true, false, null, null, null));

        Assert.assertTrue(e.getMessage().endsWith(link.toString()), e.getMessage());
        Assert.assertEquals(Files.readString(safeMarker), "keep");
        Assert.assertEquals(Files.readString(innerMarker), "keep");
        Assert.assertEquals(Files.readString(outsideMarker), "keep");
        Assert.assertTrue(Files.isSymbolicLink(link));
    }

    @Test
    public void testOverwriteRefusesEntryThatIsASymbolicLink() throws Exception {
        Path base = Files.createDirectories(testBasePath.resolve("base"));
        Path real = Files.createDirectories(base.resolve("real"));
        Path marker = Files.writeString(real.resolve("marker.txt"), "keep");
        Path alias = base.resolve("alias");
        createSymbolicLinkOrSkip(alias, real);

        Assert.expectThrows(BotCommandException.class, () -> createFolders.action(RELATIVE_PATH_TYPE,
                base.toString(), List.of(new StringValue("alias")), true, false, null, null, null));

        Assert.assertEquals(Files.readString(marker), "keep");
        Assert.assertTrue(Files.isSymbolicLink(alias));
    }

    @Test
    public void testSymbolicLinkInsideIsAllowedWhenOverwriteIsOff() throws Exception {
        Path base = Files.createDirectories(testBasePath.resolve("base"));
        Path folder = Files.createDirectories(base.resolve("folder"));
        Path link = folder.resolve("link");
        createSymbolicLinkOrSkip(link, Files.createDirectories(testBasePath.resolve("outside")));

        DictionaryValue result = createFolders.action(RELATIVE_PATH_TYPE, base.toString(),
                List.of(new StringValue("folder")), false, false, null, null, null);

        Assert.assertEquals(result.get("folder").get(), folder.toString());
        Assert.assertTrue(Files.isSymbolicLink(link));
    }
}
