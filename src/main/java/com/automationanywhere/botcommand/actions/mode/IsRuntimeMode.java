package com.automationanywhere.botcommand.actions.mode;

import com.automationanywhere.botcommand.utilities.mode.RuntimeMode;
import com.automationanywhere.commandsdk.annotations.*;
import com.automationanywhere.commandsdk.annotations.rules.NotEmpty;
import com.automationanywhere.commandsdk.annotations.rules.SelectModes;
import com.automationanywhere.commandsdk.annotations.rules.SessionAllowNonExistent;
import com.automationanywhere.commandsdk.annotations.rules.SessionObject;
import com.automationanywhere.commandsdk.model.AttributeType;
import com.automationanywhere.commandsdk.model.DataType;

@BotCommand(commandType = BotCommand.CommandType.Condition)
@CommandPkg(label = "Runtime mode is", name = "mode_is", group_label = "Runtime mode",
        description = "Test the runtime mode, or the default when the session does not exist.",
        icon = "log_session.svg", node_label = "{{session}} is {{mode}}",
        documentation_url = "https://github.com/A360-Tools/Bot-Framework/blob/main/docs/mode/IsRuntimeMode.md")
public class IsRuntimeMode {
    @ConditionTest
    public boolean test(
            @Idx(index = "1", type = AttributeType.SESSION)
            @Pkg(label = "Runtime mode session", default_value_type = DataType.SESSION, default_value = "runtimeMode")
            @SessionObject @SessionAllowNonExistent RuntimeMode session,
            @Idx(index = "2", type = AttributeType.SELECT, options = {
                    @Idx.Option(index = "2.1", pkg = @Pkg(label = "DEV", value = "DEV")),
                    @Idx.Option(index = "2.2", pkg = @Pkg(label = "QA", value = "QA")),
                    @Idx.Option(index = "2.3", pkg = @Pkg(label = "PRD", value = "PRD"))})
            @Pkg(label = "Mode", default_value_type = DataType.STRING, default_value = "DEV")
            @NotEmpty @SelectModes String mode,
            @Idx(index = "3", type = AttributeType.TEXT)
            @Pkg(label = "Default mode", default_value_type = DataType.STRING, default_value = "DEV",
                    description = "DEV, QA or PRD, used when the session does not exist.")
            @NotEmpty String defaultMode) {
        return RuntimeMode.effectiveMode(session, defaultMode).equals(RuntimeMode.normalize(mode));
    }
}
