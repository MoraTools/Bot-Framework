package com.automationanywhere.botcommand.actions.device;

import com.automationanywhere.botcommand.data.Value;
import com.automationanywhere.botcommand.data.impl.DictionaryValue;
import com.automationanywhere.botcommand.data.impl.StringValue;
import com.automationanywhere.botcommand.exception.BotCommandException;
import com.automationanywhere.commandsdk.annotations.*;
import com.automationanywhere.commandsdk.annotations.rules.*;
import com.automationanywhere.commandsdk.model.AttributeType;
import com.automationanywhere.commandsdk.model.DataType;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Sumit Kumar
 */

@BotCommand
@CommandPkg(label = "Create Folders",
        node_label = "By {{pathType}} present in {{folderPaths}} | and  base {{baseFolderPath}}  " +
                "{{overwriteExisting}}",
        description = "Creates directories by path, including any necessary but nonexistent parent directories.",
        icon = "create_folders.svg", name = "device_create_folders",
        group_label = "Device",
        documentation_url = "https://github.com/A360-Tools/Bot-Framework/blob/main/docs/device/CreateFolders.md"

)
public class CreateFolders {
    private static final String PATH_RELATIVE = "RELATIVE";
    private static final String PATH_ABSOLUTE = "ABSOLUTE";

    @Execute
    public DictionaryValue action(
            @Idx(index = "1", type = AttributeType.SELECT, options = {
                    @Idx.Option(index = "1.1", pkg = @Pkg(label = "Relative Path", value =
                            PATH_RELATIVE)),
                    @Idx.Option(index = "1.2", pkg = @Pkg(label = "Absolute Path", value =
                            PATH_ABSOLUTE))})
            @Pkg(label = "Creation mode",
                    default_value = PATH_RELATIVE, default_value_type = DataType.STRING)
            @NotEmpty
            @SelectModes
            String pathType,

            @Idx(index = "1.1.1", type = AttributeType.FILE)
            @Pkg(label = "Enter base folder path", description = "Root folder path where folders will be created")
            @NotEmpty
            @FileFolder
            String baseFolderPath,

            @Idx(index = "2", type = AttributeType.LIST)
            @Pkg(label = "Folder names or path", description = "E.g. Input, Output, Parent\\Child\\Input ")
            @NotEmpty
            @ListType(DataType.STRING)
            List<Value> folderPaths,

            @Idx(index = "3", type = AttributeType.BOOLEAN)
            @Pkg(label = "Overwrite existing folders",
                    description = "Whether to overwrite existing folders if they already exist."
                    , node_label = "Overwrite folders if they already exist",
                    default_value = "false", default_value_type = DataType.BOOLEAN)
            Boolean overwriteExisting,

            @Idx(index = "4", type = AttributeType.CHECKBOX)
            @Pkg(label = "Append created folder details to existing config")
            Boolean appendToExistingDictionary,

            @Idx(index = "4.1", type = AttributeType.VARIABLE)
            @Pkg(label = "Existing config dictionary", description = "Appends created folder" +
                    " details: Key= folder name | value = folder path")
            @VariableType(DataType.DICTIONARY)
            Map<String, Value> configDictionary,


            @Idx(index = "4.2", type = AttributeType.TEXT)
            @Pkg(label = "Key suffix", description = "Adds suffix to the keys of generated folders",
                    default_value = "_Path", default_value_type = DataType.STRING)
            String suffix,

            @Idx(index = "4.3", type = AttributeType.TEXT)
            @Pkg(label = "Key prefix", description = "Adds prefix to the keys of generated folders")
            String prefix
    ) {
        try {
            Map<String, Value> returnDictionary = new HashMap<>();
            if (configDictionary == null) {
                configDictionary = new HashMap<>();
            }

            if (suffix == null) {
                suffix = "";
            }

            if (prefix == null) {
                prefix = "";
            }

            boolean relative = PATH_RELATIVE.equalsIgnoreCase(pathType);
            if (relative && (baseFolderPath == null || baseFolderPath.isBlank())) {
                throw new BotCommandException("Base folder path must not be blank");
            }
            Path basePath = relative ? Paths.get(baseFolderPath).toAbsolutePath() : null;
            Path realBasePath = relative ? resolveExistingParents(basePath) : null;
            // Validate the whole batch before overwrite can delete any folder.
            List<Path> directoryPaths = new ArrayList<>();
            for (Value folderPathValue : folderPaths) {
                String folderPath = folderPathValue.get().toString();
                if (folderPath.isBlank()) {
                    throw new BotCommandException("Folder path must not be blank");
                }
                Path directoryPath = Paths.get(folderPath);
                if (relative) {
                    if (directoryPath.isAbsolute()) {
                        throw new BotCommandException("Relative folder path must not be absolute: " + folderPath);
                    }
                    directoryPath = Paths.get(baseFolderPath, folderPath);
                    Path normalizedPath = directoryPath.toAbsolutePath().normalize();
                    if (normalizedPath.equals(basePath.normalize()) || !normalizedPath.startsWith(basePath.normalize())) {
                        throw new BotCommandException("Folder path must be inside the base folder: " + folderPath);
                    }
                }
                Path realDirectoryPath = resolveExistingParents(directoryPath.toAbsolutePath());
                if (realDirectoryPath.getParent() == null || (relative &&
                        (realDirectoryPath.equals(realBasePath) || !realDirectoryPath.startsWith(realBasePath)))) {
                    throw new BotCommandException("Folder path must not be a root, the base folder, or outside the base folder: " + folderPath);
                }
                directoryPaths.add(directoryPath);
            }

            for (Path directoryPath : directoryPaths) {
                if (Files.exists(directoryPath) && overwriteExisting) {
                    FileUtils.deleteDirectory(directoryPath.toFile());
                }

                Files.createDirectories(directoryPath);
                String key = prefix + FilenameUtils.getName(directoryPath.toString()) + suffix;
                if (appendToExistingDictionary) {
                    configDictionary.put(key, new StringValue(directoryPath.toString()));
                }
                returnDictionary.put(key, new StringValue(directoryPath.toString()));
            }

            return new DictionaryValue(returnDictionary);
        } catch (Exception e) {
            throw new BotCommandException(e.getMessage(), e);
        }

    }

    private Path resolveExistingParents(Path path) throws IOException {
        Path existingPath = path;
        while (!Files.exists(existingPath, LinkOption.NOFOLLOW_LINKS)) {
            existingPath = existingPath.getParent();
        }
        if (!Files.isDirectory(existingPath)) {
            throw new BotCommandException("Folder path contains a file or an invalid symbolic link: " + path);
        }
        return existingPath.toRealPath().resolve(existingPath.relativize(path)).normalize();
    }
}
